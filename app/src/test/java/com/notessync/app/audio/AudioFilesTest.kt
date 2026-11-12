package com.notessync.app.audio

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AudioFilesTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun id() = UUID.randomUUID().toString()
    private fun rejected(block: () -> Unit) { try { block(); fail("Invalid audio was accepted") } catch (_: IOException) { } }
    @Test fun `validated bytes are copied atomically and the draft is retained until the DB decision`() {
        // A fake metadata probe isolates storage semantics; this is not a real codec recording.
        val files = AudioFiles(app) { AudioMetadata(1_000, "AUDIO/MP4") }
        val draft = files.create(id()); val bytes = ByteArray(4097) { (it % 251).toByte() }; File(draft.path).writeBytes(bytes)
        val stored = files.store(draft, id())
        assertArrayEquals(bytes, stored.file.readBytes()); assertEquals(bytes.size.toLong(), stored.sizeBytes)
        assertEquals("audio/mp4", stored.mimeType); assertTrue(File(draft.path).isFile)
        assertEquals(File(app.filesDir, "attachments").canonicalFile, stored.file.canonicalFile.parentFile)
        files.discard(draft); assertFalse(File(draft.path).exists()); assertTrue(stored.file.exists())
    }
    @Test fun `empty and oversized sparse files are rejected before any metadata parsing or copy`() {
        var probes = 0
        val files = AudioFiles(app) { probes++; AudioMetadata(1_000, "audio/mp4") }
        val draft = files.create(id()); rejected { files.store(draft, id()) }
        RandomAccessFile(draft.path, "rw").use { it.setLength(MAX_AUDIO_BYTES + 1) }
        rejected { files.store(draft, id()) }; assertEquals(0, probes)
        files.discard(draft)
    }
    @Test fun `video invalid duration and unsupported metadata never become attachments`() {
        var metadata = AudioMetadata(1, "audio/mp4")
        val files = AudioFiles(app) { metadata }
        listOf(AudioMetadata(0, "audio/mp4"), AudioMetadata(MAX_AUDIO_DURATION_MILLIS + 1, "audio/mp4"),
            AudioMetadata(1_000, "video/mp4", true), AudioMetadata(1_000, "application/octet-stream")).forEach { invalid ->
            metadata = invalid; val draft = files.create(id()); File(draft.path).writeText("byte fixture")
            rejected { files.store(draft, id()) }; assertTrue(File(draft.path).exists()); files.discard(draft)
        }
    }
    @Test fun `foreign draft paths and failed finalization preserve the original and leave no pending copy`() {
        val files = AudioFiles(app) { AudioMetadata(1_000, "audio/mp4") }
        val foreign = File(app.cacheDir, "foreign-sound").apply { writeText("keep") }
        rejected { files.store(AudioDraft(id(), foreign.absolutePath), id()) }
        rejected { files.discard(AudioDraft(id(), foreign.absolutePath)) }; assertEquals("keep", foreign.readText())
        val draft = files.create(id()); File(draft.path).writeText("audio fixture")
        val targetId = id(); val root = File(app.filesDir, "attachments").apply { mkdirs() }
        File(root, targetId).mkdir()
        rejected { files.store(draft, targetId) }
        assertFalse(File(root, "$targetId.pending").exists()); assertEquals("audio fixture", File(draft.path).readText())
        files.discard(draft)
    }
}
