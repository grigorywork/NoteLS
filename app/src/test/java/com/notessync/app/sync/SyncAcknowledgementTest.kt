package com.notessync.app.sync

import com.notessync.app.sync.core.*
import com.notessync.app.sync.crypto.SessionCrypto
import com.notessync.app.sync.protocol.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class SyncAcknowledgementTest {
    private fun wire(vararg messages: SyncMessage): ByteArray {
        val out = ByteArrayOutputStream(); val writer = FrameTransport(ByteArrayInputStream(ByteArray(0)), out)
        messages.forEach(writer::sendMessage); return out.toByteArray()
    }
    @Test fun `unsuccessful connection never acknowledges sent edits and success acknowledges only after peer DONE`() = runBlocking {
        for (finish in listOf(false, true)) {
            val messages = mutableListOf<SyncMessage>(SyncMessage.Hello("peer", "Peer"), SyncMessage.Changes(ChangeSet()), SyncMessage.ChangesDone, SyncMessage.ChangesDone)
            if (finish) messages += SyncMessage.Done(0, 0, 0, 0)
            val input = ByteArrayInputStream(wire(*messages.toTypedArray())); var acknowledged = 0
            val source = object : SyncDataSource by FakeSyncDataSource() {
                override suspend fun getChangesSince(since: Long) = ChangeSet(notes = listOf(NoteDto("id", "Title", "body".repeat(10_000), false, false, 0, 100)))
                override suspend fun markAcknowledged(versions: SyncAcknowledgements) {
                    assertEquals(0, input.available()); assertEquals(listOf(SyncVersion("id", 100)), versions.notes); acknowledged++
                }
            }
            val result = runCatching { SyncSession(FrameTransport(input, ByteArrayOutputStream()), source, "local", "Local", { 0 }).runAsServer() }
            assertEquals(finish, result.isSuccess); assertEquals(if (finish) 1 else 0, acknowledged)
        }
    }
    @Test fun `known-device ID mismatch is rejected before any local data access`() = runBlocking {
        var accesses = 0
        val source = object : SyncDataSource by FakeSyncDataSource() {
            override suspend fun ensureSyncIds() { accesses++ }
            override suspend fun getChangesSince(since: Long): ChangeSet { accesses++; return ChangeSet() }
        }
        val session = SyncSession(FrameTransport(ByteArrayInputStream(wire(SyncMessage.Hello("impostor", "Peer", trustedPairing = true))), ByteArrayOutputStream()),
            source, "local", "Local", { 0 }, expectedPeerId = "known")
        assertTrue(runCatching { session.runAsServer() }.isFailure); assertEquals(0, accesses)
    }
    @Test fun `resumption secret has a separate purpose and is identical in both directions`() {
        val root = ByteArray(32) { it.toByte() }
        val client = SessionCrypto.derive(root, true); val server = SessionCrypto.derive(root, false)
        assertEquals(client.resumptionSecret(), server.resumptionSecret())
        assertNotEquals(root.joinToString("") { "%02x".format(it) }, client.resumptionSecret())
        assertNotEquals(client.resumptionSecret(), SessionCrypto.derive(ByteArray(32) { 7 }, true).resumptionSecret())
        assertFalse((SyncMessage.decode("{\"type\":\"hello\",\"deviceId\":\"old\",\"deviceName\":\"Old\",\"protocol\":2}".toByteArray()) as SyncMessage.Hello).trustedPairing)
    }
}
