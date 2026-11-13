package com.notessync.app.sync

import com.notessync.app.sync.core.*
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.util.UUID

class SyncSafetyTest {
    private val id = UUID.randomUUID().toString()
    private fun meta(bytes: ByteArray) = AttachmentDto(id, "note", "image/jpeg", bytes.size.toLong(), Hashing.sha256Hex(bytes))
    private fun note(meta: AttachmentDto) = NoteDto("note", "Title", "Private content", false, false, 0, 1, attachments = listOf(meta))
    private class Source : SyncDataSource by FakeSyncDataSource() {
        var initialized = 0; var applied = 0; var writes = 0; var reads = 0
        var local = ChangeSet(); var missing = emptyList<AttachmentDto>()
        override suspend fun ensureSyncIds() { initialized++ }
        override suspend fun getChangesSince(since: Long) = local
        override suspend fun applyChanges(changes: ChangeSet): ApplyStats { applied++; return ApplyStats(missingAttachments = missing) }
        override suspend fun writeAttachmentBytes(meta: AttachmentDto, data: ByteArray) { writes++ }
        override suspend fun readAttachmentBytes(attachmentSyncId: String): ByteArray { reads++; return byteArrayOf(1) }
    }
    private fun wire(vararg messages: SyncMessage, chunks: List<ByteArray> = emptyList()): ByteArray {
        val bytes = ByteArrayOutputStream(); val transport = FrameTransport(ByteArrayInputStream(ByteArray(0)), bytes)
        messages.forEach(transport::sendMessage); chunks.forEach { transport.sendChunk(id, it, true) }
        return bytes.toByteArray()
    }
    private fun session(input: ByteArray, source: Source) = SyncSession(FrameTransport(ByteArrayInputStream(input), ByteArrayOutputStream()), source, "local", "Local", { 0 })
    private val hello = SyncMessage.Hello("peer", "Peer")

    @Test fun `unsupported peer protocol does not initialize or read local data`() = runBlocking {
        val source = Source(); assertTrue(runCatching { session(wire(hello.copy(protocol = 1)), source).runAsServer() }.isFailure)
        assertEquals(0, source.initialized); assertEquals(0, source.reads); assertEquals(0, source.applied)
    }
    @Test fun `truncated changes are not applied before their terminator`() = runBlocking {
        val source = Source(); val set = ChangeSet(notes = listOf(note(meta(byteArrayOf(1)))))
        assertTrue(runCatching { session(wire(hello, SyncMessage.Changes(set)), source).runAsClient() }.isFailure)
        assertEquals(0, source.applied)
    }
    @Test fun `oversized negative wrong-owner duplicate and path metadata are rejected before applying`() = runBlocking {
        val valid = meta(byteArrayOf(1))
        val sets = listOf(
            ChangeSet(notes = listOf(note(valid.copy(sizeBytes = Long.MAX_VALUE)))),
            ChangeSet(notes = listOf(note(valid.copy(sizeBytes = -1)))),
            ChangeSet(notes = listOf(note(valid.copy(noteSyncId = "other")))),
            ChangeSet(notes = listOf(note(valid), note(valid))),
            ChangeSet(notes = listOf(note(valid.copy(syncId = "../../data")))),
            ChangeSet(notes = listOf(note(valid.copy(sha256 = "not-a-hash"))))
        )
        for (set in sets) {
            val source = Source()
            assertTrue(runCatching { session(wire(hello, SyncMessage.Changes(set), SyncMessage.ChangesDone), source).runAsClient() }.isFailure)
            assertEquals(0, source.applied); assertEquals(0, source.writes)
        }
    }
    @Test fun `actual oversized truncated or corrupt file is not written`() = runBlocking {
        val advertised = meta(byteArrayOf(1, 2))
        for (bytes in listOf(byteArrayOf(1, 2, 3), byteArrayOf(1), byteArrayOf(9, 9))) {
            val source = Source().apply { missing = listOf(advertised) }
            val input = wire(hello, SyncMessage.Changes(ChangeSet(notes = listOf(note(advertised)))), SyncMessage.ChangesDone, chunks = listOf(bytes))
            assertTrue(runCatching { session(input, source).runAsClient() }.isFailure)
            assertEquals(0, source.writes)
        }
    }
    @Test fun `unoffered and repeated attachment requests cannot repeatedly read local files`() = runBlocking {
        val attachment = meta(byteArrayOf(1))
        for (offer in listOf(false, true)) {
            val source = Source().apply { if (offer) local = ChangeSet(notes = listOf(note(attachment))) }
            val input = wire(hello, SyncMessage.Changes(ChangeSet()), SyncMessage.ChangesDone,
                SyncMessage.AttachmentRequest(id), SyncMessage.AttachmentRequest(id))
            assertTrue(runCatching { session(input, source).runAsServer() }.isFailure)
            assertEquals(if (offer) 1 else 0, source.reads)
        }
    }
    @Test fun `unknown kind and oversized chunk are rejected before reading or allocating payload`() {
        for ((kind, size) in listOf(99 to 1, 2 to FrameTransport.MAX_FRAME_BYTES, 1 to -1)) {
            val input = ByteArrayInputStream(ByteBuffer.allocate(5).put(kind.toByte()).putInt(size).array())
            assertThrows(IllegalArgumentException::class.java) { FrameTransport(input, ByteArrayOutputStream()).readFrame() }
            assertEquals(0, input.available())
        }
    }
    @Test fun `invalid chunk flags empty progress and malformed UTF8 are refused`() {
        fun raw(payload: ByteArray) = ByteBuffer.allocate(5 + payload.size).put(2).putInt(payload.size).put(payload).array()
        listOf(byteArrayOf(2) + id.toByteArray(), byteArrayOf(0) + id.toByteArray()).forEach {
            assertThrows(IllegalArgumentException::class.java) { FrameTransport(ByteArrayInputStream(raw(it)), ByteArrayOutputStream()).readFrame() }
        }
        assertTrue(runCatching { SyncMessage.decode(byteArrayOf(0xc3.toByte(), 0x28)) }.isFailure)
    }
}
