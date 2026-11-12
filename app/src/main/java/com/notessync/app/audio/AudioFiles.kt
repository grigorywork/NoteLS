package com.notessync.app.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import com.notessync.app.data.attachments.PhotoFileWriter
import java.io.File
import java.io.IOException
import java.util.UUID

/** Private drafts are never exported or included in backup before a validated DB attachment exists. */
class AudioFiles(context: Context, private val inspect: (File) -> AudioMetadata = ::inspectAudio) : AudioDraftStore {
    private val pending = File(context.applicationContext.filesDir, "audio-capture")
    private val current = File(pending, processId)
    private val attachments = File(context.applicationContext.filesDir, "attachments")
    override fun create(token: String): AudioDraft {
        requireId(token)
        if (pending.exists()) pending.listFiles()?.filter { it.name != processId && UUID_PATTERN.matches(it.name) }
            ?.forEach { old -> if (old.canonicalFile.parentFile == pending.canonicalFile) old.deleteRecursively() }
        if (!current.isDirectory && !current.mkdirs()) throw IOException("Cannot create audio draft directory")
        if (current.usableSpace < MAX_AUDIO_BYTES + 2L * 1024 * 1024) throw AudioStorageFullException()
        val file = File(current, "$token.m4a")
        if (!file.createNewFile()) throw IOException("Audio draft already exists")
        return AudioDraft(token, file.absolutePath)
    }
    override fun discard(draft: AudioDraft) { ownDraft(draft).delete() }
    fun store(draft: AudioDraft, attachmentId: String): StoredAudio {
        requireId(attachmentId)
        val source = ownDraft(draft)
        if (!source.isFile || source.length() !in 1..MAX_AUDIO_BYTES) throw IOException("Audio size is invalid")
        val metadata = inspect(source)
        if (metadata.durationMillis !in 1..MAX_AUDIO_DURATION_MILLIS || metadata.hasVideo || !metadata.mimeType.startsWith("audio/", ignoreCase = true)) {
            throw IOException("The recording is not valid audio")
        }
        val target = File(attachments, attachmentId)
        val size = source.inputStream().use { PhotoFileWriter(MAX_AUDIO_BYTES).write(it, target) }
        return StoredAudio(target, metadata.mimeType.lowercase(java.util.Locale.ROOT), size)
    }
    private fun ownDraft(draft: AudioDraft): File {
        requireId(draft.token)
        val expected = File(current, "${draft.token}.m4a").canonicalFile
        if (File(draft.path).canonicalFile != expected || expected.parentFile != current.canonicalFile) throw IOException("Foreign audio draft")
        return expected
    }
    private fun requireId(id: String) { require(UUID_PATTERN.matches(id)) { "Invalid audio identity" } }
    companion object {
        private val processId = UUID.randomUUID().toString()
        private val UUID_PATTERN = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
    }
}
data class AudioMetadata(val durationMillis: Long, val mimeType: String, val hasVideo: Boolean = false)
data class StoredAudio(val file: File, val mimeType: String, val sizeBytes: Long)

private fun inspectAudio(file: File): AudioMetadata {
    val reader = MediaMetadataRetriever()
    try {
        reader.setDataSource(file.absolutePath)
        check(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes")
        val mime = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
        val video = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
        return AudioMetadata(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
            if (!video && mime == "video/mp4") "audio/mp4" else mime, video)
    } finally { reader.release() }
}
