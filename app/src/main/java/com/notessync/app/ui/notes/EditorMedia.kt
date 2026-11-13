package com.notessync.app.ui.notes

import com.notessync.app.audio.*
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.notes.photos.PhotoDisplaySize
import androidx.room.withTransaction
import com.notessync.app.settings.SettingsRepository
import android.app.Application
import android.content.Intent
import com.notessync.app.data.attachments.CameraCaptureStore
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import com.notessync.app.data.attachments.PhotoAttachmentStore
import com.notessync.app.data.attachments.PhotoTooLargeException
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

internal interface EditorMediaParent {
    val currentNoteId: Long
    val syncId: String
    val loaded: Boolean
    val isReady: Boolean
    val accessDenied: Boolean
    val deleted: Boolean
    val isClosing: Boolean
    val exists: Boolean
    suspend fun accessibleParent(): NoteEntity?
    suspend fun persistParent()
    suspend fun saveParent()
    fun report(message: EditorMessage)
}

/** Owns media resources; database changes retain the editor's single save mutex. */
internal class EditorMedia(private val app: Application, private val db: AppDatabase,
    private val savedState: SavedStateHandle, private val scope: CoroutineScope, private val saveMutex: Mutex,
    private val parent: EditorMediaParent, audioDependencies: EditorAudioDependencies?) {
    private val noteDao = db.noteDao()
    private val attachmentDao = db.attachmentDao()
    private val currentNoteId: Long get() = parent.currentNoteId
    private val syncId: String get() = parent.syncId
    private val loaded: Boolean get() = parent.loaded
    private val isReady: Boolean get() = parent.isReady
    private val accessDenied: Boolean get() = parent.accessDenied
    private val deleted: Boolean get() = parent.deleted
    private val isClosing: Boolean get() = parent.isClosing
    private val exists: Boolean get() = parent.exists
    private suspend fun accessibleParent() = parent.accessibleParent()
    private val photoStore = PhotoAttachmentStore(app)
    private val audioFiles = audioDependencies?.files ?: AudioFiles(app)
    val audioPlayback = AudioPlayback(audioDependencies?.playbackFactory ?: AndroidAudioPlaybackFactory(app), ::resolveAudio,
        audioDependencies?.dispatcher ?: Dispatchers.IO)
    val audioCapture = AudioCapture(audioDependencies?.captureFactory ?: AndroidAudioCaptureFactory(app), audioFiles,
        audioDependencies?.clock ?: android.os.SystemClock::elapsedRealtime, ::commitRecordedAudio,
        beforeStart = { ensureAudioParent(); audioPlayback.stop().await() }, dispatcher = audioDependencies?.dispatcher ?: Dispatchers.IO)
    private val cameraStore = CameraCaptureStore(app)
    private var cameraToken: String? = savedState["cameraCapture"]
    private var cameraProcessing = false
    var isPhotoBusy by mutableStateOf(cameraToken != null)
        private set

    /** Reserve the capture synchronously so Back/pause can cancel a pending note save as well. */
    fun startAudioRecording() {
        if (!SettingsRepository.get(app).state.value.audioRecording || isClosing || isPhotoBusy || !loaded || accessDenied || deleted) return
        audioCapture.start()
    }
    private suspend fun ensureAudioParent() = saveMutex.withLock {
        check(SettingsRepository.get(app).state.value.audioRecording && !isClosing && !isPhotoBusy && loaded && !accessDenied && !deleted)
        check(!exists || accessibleParent() != null)
        withContext(NonCancellable) { parent.persistParent() }
        check(!isClosing && !deleted && !accessDenied && accessibleParent() != null)
    }

    private suspend fun commitRecordedAudio(draft: AudioDraft): Boolean = saveMutex.withLock {
        if (!loaded || accessDenied || deleted || accessibleParent() == null) return@withLock false
        withContext(NonCancellable) {
            val attachmentId = UUID.randomUUID().toString()
            val stored = withContext(Dispatchers.IO) { audioFiles.store(draft, attachmentId) }
            var committed = false
            try {
                committed = db.withTransaction {
                    if (accessibleParent() == null) return@withTransaction false
                    attachmentDao.upsert(AttachmentEntity(syncId = attachmentId, noteId = currentNoteId,
                        filePath = stored.file.absolutePath, mimeType = stored.mimeType, sizeBytes = stored.sizeBytes))
                    noteDao.markContentChanged(currentNoteId)
                    true
                }
                if (committed) BackupManager.noteChanged()
                committed
            } finally { if (!committed) withContext(Dispatchers.IO) { stored.file.delete() } }
        }
    }
    private suspend fun resolveAudio(attachmentSyncId: String): String? = withContext(Dispatchers.IO) {
        val row = attachmentDao.getBySyncId(attachmentSyncId) ?: return@withContext null
        if (row.noteId != currentNoteId || !row.mimeType.startsWith("audio/", ignoreCase = true)) return@withContext null
        if (noteDao.getAccess(currentNoteId)?.allowed(currentNoteId, syncId, ProtectionSession.state.value) != true) return@withContext null
        val root = File(app.filesDir, "attachments").canonicalFile
        val file = File(row.filePath).canonicalFile
        file.takeIf { it.toPath().startsWith(root.toPath()) && it != root && it.isFile && it.length() in 1..MAX_AUDIO_BYTES }?.absolutePath
    }

    fun attach(uri: Uri) {
        if (isPhotoBusy || isClosing) return
        isPhotoBusy = true
        scope.launch {
            try { importPhoto { photoStore.import(uri, it) } }
            finally { isPhotoBusy = false }
        }
    }

    private suspend fun importPhoto(import: (String) -> com.notessync.app.data.attachments.StoredPhoto) {
        try {
            saveMutex.withLock {
                if (!loaded || accessDenied || deleted) return@withLock
                if (exists && accessibleParent() == null) {
                    parent.report(EditorMessage.PHOTO_IMPORT_FAILED)
                    return@withLock
                }
                // Exit/delete must wait until both the bounded copy and DB transaction finish.
                withContext(NonCancellable) {
                    parent.persistParent()
                    if (deleted || accessDenied || accessibleParent() == null) return@withContext
                    val attachmentSyncId = UUID.randomUUID().toString()
                    val stored = withContext(Dispatchers.IO) { import(attachmentSyncId) }
                    try {
                        db.withTransaction {
                            check(accessibleParent() != null) { "The attachment parent is no longer accessible" }
                            attachmentDao.upsert(AttachmentEntity(syncId = attachmentSyncId,
                                noteId = currentNoteId, filePath = stored.file.absolutePath,
                                mimeType = stored.mimeType, sizeBytes = stored.sizeBytes))
                            noteDao.markContentChanged(currentNoteId)
                        }
                    } catch (error: Exception) {
                        withContext(Dispatchers.IO) { stored.file.delete() }
                        throw error
                    }
                    BackupManager.noteChanged()
                }
            }
        } catch (_: PhotoTooLargeException) {
            parent.report(EditorMessage.PHOTO_TOO_LARGE)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            parent.report(EditorMessage.PHOTO_IMPORT_FAILED)
        }
    }

    suspend fun prepareCameraCapture(): Uri? {
        if (isPhotoBusy || isClosing || !loaded || accessDenied || deleted) return null
        isPhotoBusy = true
        return try {
            // Persist the editor before launching another app; note ID and token survive process recreation.
            parent.saveParent()
            val uri = withContext(NonCancellable) {
                val token = withContext(Dispatchers.IO) { cameraStore.create() }
                cameraToken = token
                savedState["cameraCapture"] = token
                withContext(Dispatchers.IO) { cameraStore.uri(token) }
            }
            currentCoroutineContext().ensureActive()
            uri
        } catch (error: Exception) {
            cancelCameraCapture()
            if (error is CancellationException) throw error
            parent.report(EditorMessage.CAMERA_FAILED)
            null
        }
    }

    fun finishCameraCapture(success: Boolean) = scope.launch {
        if (cameraProcessing) return@launch
        val token = cameraToken ?: return@launch
        cameraProcessing = true
        try {
            if (success) {
                androidx.compose.runtime.snapshotFlow { isReady || accessDenied }.first { it }
                importPhoto { cameraStore.import(token, it) }
            }
        }
        finally {
            cancelCameraCapture()
            cameraProcessing = false
        }
    }

    private suspend fun cancelCameraCapture() = withContext(NonCancellable) {
        val token = cameraToken
        withContext(Dispatchers.IO) {
            if (token != null) {
                runCatching { app.revokeUriPermission(cameraStore.uri(token), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                runCatching { cameraStore.delete(token) }
            }
        }
        cameraToken = null
        savedState["cameraCapture"] = null
        isPhotoBusy = false
    }

    suspend fun savePhotoSize(photo: AttachmentEntity, requested: PhotoDisplaySize): Boolean {
        return try {
            saveMutex.withLock {
                if (deleted || !loaded || accessDenied || photo.noteId != currentNoteId) return@withLock false
                val size = requested.bounded()
                withContext(NonCancellable) {
                    db.withTransaction {
                        val current = attachmentDao.getBySyncId(photo.syncId) ?: return@withTransaction false
                        if (current.id != photo.id || current.noteId != currentNoteId) return@withTransaction false
                        if (current.displayWidthDp == size.width && current.displayHeightDp == size.height) return@withTransaction true
                        if (attachmentDao.updateDisplaySize(current.id, currentNoteId, size.width, size.height) != 1) return@withTransaction false
                        noteDao.markContentChanged(currentNoteId)
                        BackupManager.noteChanged()
                        true
                    }
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            parent.report(EditorMessage.PHOTO_SIZE_FAILED)
            false
        }
    }

    fun removeAttachment(attachment: AttachmentEntity) {
        if (attachment.noteId != currentNoteId) return
        if (audioPlayback.state.value.key == attachment.syncId) audioPlayback.stop()
        scope.launch {
            saveMutex.withLock {
                withContext(NonCancellable) {
                    var removed: AttachmentEntity? = null
                    db.withTransaction {
                        if (accessibleParent() == null) return@withTransaction
                        val fresh = attachmentDao.getBySyncId(attachment.syncId) ?: return@withTransaction
                        if (fresh.id != attachment.id || fresh.noteId != currentNoteId) return@withTransaction
                        attachmentDao.deleteById(fresh.id)
                        noteDao.markContentChanged(currentNoteId)
                        removed = fresh
                    }
                    removed?.let { row ->
                        BackupManager.noteChanged()
                        db.withTransaction { NoteActions.deleteUnreferencedFiles(app, db, listOf(row.filePath)) }
                    }
                }
            }
        }
    }

    fun close() { audioPlayback.close(); audioCapture.close() }
}
