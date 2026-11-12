package com.notessync.app.audio

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AudioEditorPersistenceTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: NoteEditorViewModel
    private val captures = java.util.concurrent.CopyOnWriteArrayList<Capture>()
    private val players = java.util.concurrent.CopyOnWriteArrayList<Player>()
    private var inspect: (File) -> AudioMetadata = { AudioMetadata(1_000, "audio/mp4") }
    private val bytes = ByteArray(4097) { (it % 251).toByte() } // Binary storage fixture; not a real microphone/codec.
    private inner class Capture : AudioCaptureBackend {
        var path = ""; var releases = 0
        override fun prepare(path: String) { this.path = path; File(path).writeBytes(bytes) }
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() { releases++ }
    }
    private class Player(val callbacks: AudioPlaybackCallbacks) : AudioPlaybackBackend {
        var releases = 0; var plays = 0
        override fun prepare(path: String) = Unit
        override fun play(positionMillis: Long) { plays++ }
        override val positionMillis = 0L
        override val durationMillis = 1000L
        override fun release() { releases++ }
    }
    @Before fun before() {
        Dispatchers.setMain(dispatcher); ProtectionSession.lockAll()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setBackupFrequency(BackupFrequency.MANUAL); setAudioRecording(true) }
        vm = NoteEditorViewModel(app, SavedStateHandle(), EditorAudioDependencies(AudioFiles(app) { inspect(it) },
            AudioCaptureFactory { Capture().also(captures::add) }, AudioPlaybackFactory { Player(it).also(players::add) }, dispatcher, { 100 }))
        models.put("editor", vm); dispatcher.scheduler.runCurrent(); assertTrue(vm.isReady)
    }
    @After fun after() {
        runTest(dispatcher) { vm.audioCapture.cancel().await(); vm.audioPlayback.stop().await(); vm.saveNow(force = true) }
        models.clear(); dispatcher.scheduler.runCurrent(); AppDatabase.closeInstance(); ProtectionSession.lockAll(); Dispatchers.resetMain()
    }
    private suspend fun capturePhase(phase: AudioCapturePhase) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { vm.audioCapture.state.first { it.phase == phase } }
    }
    private suspend fun playingPhase(phase: AudioPlaybackPhase) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { vm.audioPlayback.state.first { it.phase == phase } }
    }
    private suspend fun start() { vm.startAudioRecording(); capturePhase(AudioCapturePhase.RECORDING) }
    private fun files(): Set<String> = File(app.filesDir, "attachments").listFiles()?.map { it.name }?.toSet().orEmpty()

    @Test fun `audio is attached once marked for sync and retained on exit from an otherwise empty note`() = runTest(dispatcher) {
        assertNotNull(NoteEditorViewModel::class.java.getConstructor(Application::class.java, SavedStateHandle::class.java))
        start(); val id = vm.currentNoteId; val finished = vm.audioCapture.finish(); assertTrue(finished.await())
        assertTrue(vm.audioCapture.finish().await())
        val db = AppDatabase.get(app); val row = db.attachmentDao().getByNoteId(id).single()
        assertEquals("audio/mp4", row.mimeType); assertArrayEquals(bytes, File(row.filePath).readBytes())
        assertFalse(File(captures.single().path).exists()); assertEquals(1, captures.single().releases)
        assertTrue(db.noteDao().getById(id)!!.dirty); assertTrue(db.attachmentDao().observeFirstPhotos().first().isEmpty())
        assertTrue(vm.flushOnExit()); assertFalse(db.noteDao().getById(id)!!.isDeleted)
    }
    @Test fun `SQL failure removes both copies and another manual recording succeeds`() = runTest(dispatcher) {
        val db = AppDatabase.get(app)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_audio BEFORE INSERT ON attachments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val previous = files(); start(); assertFalse(vm.audioCapture.finish().await())
        assertTrue(db.attachmentDao().getByNoteId(vm.currentNoteId).isEmpty()); assertEquals(previous, files())
        assertFalse(File(captures.single().path).exists()); assertEquals(1, captures.single().releases)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_audio")
        start(); assertTrue(vm.audioCapture.finish().await()); assertEquals(1, db.attachmentDao().getByNoteId(vm.currentNoteId).size)
    }
    @Test fun `protecting a parent notebook invalidates access and discards an active private recording`() = runTest(dispatcher) {
        val db = AppDatabase.get(app); val book = NotebookEntity(name = "Work").let { it.copy(id = db.notebookDao().upsert(it)) }
        vm.assignNotebook(book); start()
        db.notebookDao().update(book.copy(isProtected = true)); capturePhase(AudioCapturePhase.IDLE)
        assertTrue(db.attachmentDao().getByNoteId(vm.currentNoteId).isEmpty()); assertFalse(File(captures.single().path).exists())
        assertEquals(1, captures.single().releases); assertTrue(db.notebookDao().getById(book.id)!!.isProtected)
    }
    @Test fun `purge during file validation cannot attach or resurrect audio`() = runTest(dispatcher) {
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        inspect = { entered.countDown(); check(proceed.await(10, TimeUnit.SECONDS)); AudioMetadata(1000, "audio/mp4") }
        val previous = files(); start(); val id = vm.currentNoteId; val finished = vm.audioCapture.finish()
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            val actions = NoteActions(app); actions.apply(setOf(id), NoteActions.Action.TRASH); actions.apply(setOf(id), NoteActions.Action.PURGE)
        } finally { proceed.countDown() }
        assertFalse(finished.await())
        val db = AppDatabase.get(app); assertTrue(db.noteDao().getById(id)!!.isPurged)
        assertTrue(db.attachmentDao().getByNoteId(id).isEmpty()); assertFalse(File(captures.single().path).exists())
        assertEquals(previous, files())
    }
    @Test fun `a reused note id cannot be overwritten discarded or deleted by an old empty editor`() = runTest(dispatcher) {
        vm.saveNow(force = true); val id = vm.currentNoteId
        val db = AppDatabase.get(app); val replacement = NoteEntity(id = id, syncId = UUID.randomUUID().toString(), content = "Restored data")
        db.noteDao().upsert(replacement)
        assertTrue(vm.flushOnExit()); assertEquals(-1L, vm.deleteNow())
        assertEquals(replacement, db.noteDao().getById(id))
    }
    @Test fun `removing one shared attachment keeps live bytes and never deletes a foreign file`() = runTest(dispatcher) {
        start(); assertTrue(vm.audioCapture.finish().await())
        val db = AppDatabase.get(app); val row = db.attachmentDao().getByNoteId(vm.currentNoteId).single()
        val otherNote = db.noteDao().upsert(NoteEntity(title = "Other"))
        db.attachmentDao().upsert(row.copy(id = 0, syncId = UUID.randomUUID().toString(), noteId = otherNote))
        vm.removeAttachment(row)
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (db.attachmentDao().getBySyncId(row.syncId) != null) delay(5) } }
        assertTrue(File(row.filePath).isFile)
        val foreign = File(app.cacheDir, "foreign-audio").apply { writeText("Do not remove") }
        val invalid = AttachmentEntity(noteId = vm.currentNoteId, filePath = foreign.path, mimeType = "audio/mp4", sizeBytes = 13)
            .let { it.copy(id = db.attachmentDao().upsert(it)) }
        vm.removeAttachment(invalid)
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (db.attachmentDao().getBySyncId(invalid.syncId) != null) delay(5) } }
        assertEquals("Do not remove", foreign.readText())
    }
    @Test fun `locking an unlocked protected notebook stops playback without waiting for a new file query`() = runTest(dispatcher) {
        val db = AppDatabase.get(app); val book = NotebookEntity(name = "Private", isProtected = true).let { it.copy(id = db.notebookDao().upsert(it)) }
        ProtectionSession.unlockNotebook(book.id); vm.assignNotebook(book); start(); assertTrue(vm.audioCapture.finish().await())
        val row = db.attachmentDao().getByNoteId(vm.currentNoteId).single()
        vm.audioPlayback.play(row.syncId)
        withContext(Dispatchers.Default) { withTimeout(10_000) { while (players.isEmpty()) delay(5) } }
        players.single().callbacks.prepared(); playingPhase(AudioPlaybackPhase.PLAYING)
        ProtectionSession.lockNotebook(book.id); playingPhase(AudioPlaybackPhase.IDLE)
        assertEquals(1, players.single().plays); assertEquals(1, players.single().releases)
    }
}
