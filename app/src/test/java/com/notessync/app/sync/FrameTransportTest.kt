package com.notessync.app.sync

import com.notessync.app.sync.crypto.SessionCrypto
import com.notessync.app.sync.protocol.FrameTransport
import com.notessync.app.sync.protocol.FrameTransport.Companion.CHUNK_BYTES
import com.notessync.app.sync.protocol.FrameTransport.Frame
import com.notessync.app.sync.protocol.SyncMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

class FrameTransportTest {

    private fun transportPair(crypto: SessionCrypto? = null): Pair<FrameTransport, ByteArrayOutputStream> {
        val out = ByteArrayOutputStream()
        return FrameTransport(ByteArrayInputStream(ByteArray(0)), out, crypto) to out
    }

    @Test
    fun `сообщение проходит туда-обратно без шифрования`() {
        val (sender, out) = transportPair()
        val message = SyncMessage.Hello(deviceId = "a", deviceName = "Pixel")
        sender.sendMessage(message)

        val reader = FrameTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val frame = reader.readFrame()
        assertEquals(Frame.Message(message), frame)
    }

    @Test
    fun `сообщение проходит туда-обратно с шифрованием`() {
        val crypto = SessionCrypto.derive(ByteArray(32) { 1 }, true)
        val (sender, out) = transportPair(crypto)
        val note = com.notessync.app.sync.protocol.NoteDto(
            syncId = UUID.randomUUID().toString(), title = "Тест", content = "Содержимое",
            favorite = true, deleted = false, createdAt = 1, updatedAt = 2
        )
        val message = SyncMessage.Changes(com.notessync.app.sync.protocol.ChangeSet(notes = listOf(note)))
        sender.sendMessage(message)

        val reader = FrameTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), SessionCrypto.derive(ByteArray(32) { 1 }, false))
        assertEquals(Frame.Message(message), reader.readFrame())
    }

    @Test
    fun `бинарный кусок вложения проходит туда-обратно`() {
        val (sender, out) = transportPair()
        val syncId = UUID.randomUUID().toString()
        val data = ByteArray(CHUNK_BYTES) { it.toByte() }
        sender.sendChunk(syncId, data, last = false)
        sender.sendChunk(syncId, ByteArray(0), last = true)

        val reader = FrameTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        val first = reader.readFrame() as Frame.Chunk
        assertEquals(syncId, first.attachmentSyncId)
        assertArrayEquals(data, first.data)
        assertEquals(false, first.last)
        val last = reader.readFrame() as Frame.Chunk
        assertEquals(true, last.last)
    }

    /** Поток, который отдаёт данные по крохам — проверка сборки кадра по частям. */
    private class DribbleStream(private val source: ByteArray) : InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < source.size) source[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= source.size) return -1
            val n = minOf(len, 3, source.size - pos)
            System.arraycopy(source, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun `кадр собирается из фрагментированного потока`() {
        val (sender, out) = transportPair()
        val message = SyncMessage.Done(1, 2, 3, 4)
        sender.sendMessage(message)

        val reader = FrameTransport(DribbleStream(out.toByteArray()), ByteArrayOutputStream())
        assertEquals(Frame.Message(message), reader.readFrame())
    }

    @Test
    fun `счётчики байтов работают`() {
        val (sender, out) = transportPair()
        sender.sendMessage(SyncMessage.ChangesDone)
        assertTrue(sender.bytesSent.get() > 0)

        val reader = FrameTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream())
        reader.readFrame()
        assertEquals(sender.bytesSent.get(), reader.bytesReceived.get())
    }
    @Test fun `concurrent writers preserve authenticated counter order and all messages`() {
        val root = ByteArray(32) { 7 }; val out = ByteArrayOutputStream()
        val writer = FrameTransport(ByteArrayInputStream(ByteArray(0)), out, SessionCrypto.derive(root, true))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val futures = (0 until 4).map { worker -> executor.submit {
                start.await()
                repeat(25) { writer.sendMessage(SyncMessage.Hello("$worker-$it", "fixture")) }
            } }
            start.countDown(); futures.forEach { it.get(8, java.util.concurrent.TimeUnit.SECONDS) }
            val reader = FrameTransport(ByteArrayInputStream(out.toByteArray()), ByteArrayOutputStream(), SessionCrypto.derive(root, false))
            val identities = mutableSetOf<String>()
            repeat(100) { identities.add(((reader.readFrame() as Frame.Message).message as SyncMessage.Hello).deviceId) }
            assertEquals(100, identities.size); assertEquals(null, reader.readFrame())
            assertEquals(writer.bytesSent.get(), reader.bytesReceived.get())
        } finally { executor.shutdownNow() }
    }
}
