package com.notessync.app.sync.protocol

import com.notessync.app.i18n.uiText

import com.notessync.app.sync.crypto.SessionCrypto
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Кадр протокола: [тип: 1 байт][длина: 4 байта big-endian][полезная нагрузка].
 * Полезная нагрузка шифруется [SessionCrypto], если он задан.
 * Тип 1 — JSON-сообщение [SyncMessage], тип 2 — бинарный кусок вложения.
 */
class FrameTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val crypto: SessionCrypto? = null
) {
    sealed interface Frame {
        data class Message(val message: SyncMessage) : Frame
        data class Chunk(val attachmentSyncId: String, val data: ByteArray, val last: Boolean) : Frame
    }

    val bytesSent = AtomicLong(0)
    val bytesReceived = AtomicLong(0)

    private val sendLock = Any()

    fun sendMessage(message: SyncMessage) {
        writeFrame(KIND_MESSAGE, SyncMessage.encode(message))
    }

    /** Пустой [data] с last=true — маркер конца вложения. */
    fun sendChunk(attachmentSyncId: String, data: ByteArray, last: Boolean) {
        val idBytes = attachmentSyncId.toByteArray(Charsets.UTF_8)
        require(SAFE_ID.matches(attachmentSyncId) && data.size <= CHUNK_BYTES && (last || data.isNotEmpty())) { "Invalid attachment chunk" }
        val payload = ByteArray(1 + SYNC_ID_BYTES + data.size)
        payload[0] = if (last) 1 else 0
        idBytes.copyInto(payload, 1)
        data.copyInto(payload, 1 + SYNC_ID_BYTES)
        writeFrame(KIND_CHUNK, payload)
    }

    private fun writeFrame(kind: Byte, plainPayload: ByteArray) {
        synchronized(sendLock) {
            val size = plainPayload.size + if (crypto == null) 0 else SessionCrypto.OVERHEAD
            require(size in 1..maxFrameBytes(kind)) { uiText("Кадр больше %1\$s байт", (MAX_FRAME_BYTES)) }
            val header = byteArrayOf(
                kind,
                (size ushr 24).toByte(),
                (size ushr 16).toByte(),
                (size ushr 8).toByte(),
                size.toByte()
            )
            // Counter assignment, encryption and write share one lock and one wire order.
            val payload = crypto?.encrypt(plainPayload, header) ?: plainPayload
            output.write(header)
            output.write(payload)
            output.flush()
            bytesSent.addAndGet((header.size + payload.size).toLong())
        }
    }

    /** Читает следующий кадр; null — соединение закрыто. */
    fun readFrame(): Frame? {
        val header = ByteArray(HEADER_BYTES)
        if (!readFully(header)) return null
        val kind = header[0]
        val length = ((header[1].toInt() and 0xFF) shl 24) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or
            (header[4].toInt() and 0xFF)
        require(length in 1..maxFrameBytes(kind)) { uiText("Некорректная длина кадра: %1\$s", (length)) }
        val payload = ByteArray(length)
        if (!readFully(payload)) throw EOFException(uiText("Кадр оборвался посередине"))
        bytesReceived.addAndGet((HEADER_BYTES + length).toLong())
        val plain = crypto?.decrypt(payload, header) ?: payload
        return when (kind) {
            KIND_MESSAGE -> Frame.Message(SyncMessage.decode(plain))
            KIND_CHUNK -> {
                require(plain.size >= 1 + SYNC_ID_BYTES) { uiText("Кусок вложения слишком короткий") }
                val id = plain.copyOfRange(1, 1 + SYNC_ID_BYTES).toString(Charsets.US_ASCII)
                require(SAFE_ID.matches(id) && plain[0].toInt() in 0..1 &&
                    plain.size <= 1 + SYNC_ID_BYTES + CHUNK_BYTES && (plain[0].toInt() == 1 || plain.size > 1 + SYNC_ID_BYTES)) { "Invalid attachment chunk" }
                Frame.Chunk(
                    attachmentSyncId = id,
                    data = plain.copyOfRange(1 + SYNC_ID_BYTES, plain.size),
                    last = plain[0].toInt() == 1
                )
            }
            else -> throw IllegalArgumentException(uiText("Неизвестный тип кадра: %1\$s", (kind)))
        }
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) {
                if (offset == 0) return false
                throw EOFException(uiText("Соединение закрыто посреди кадра"))
            }
            if (read == 0) {
                val one = input.read()
                if (one < 0) {
                    if (offset == 0) return false
                    throw EOFException(uiText("Соединение закрыто посреди кадра"))
                }
                buffer[offset++] = one.toByte()
                continue
            }
            offset += read
        }
        return true
    }

    private fun maxFrameBytes(kind: Byte): Int = when (kind) {
        KIND_MESSAGE -> MAX_FRAME_BYTES
        KIND_CHUNK -> 1 + SYNC_ID_BYTES + CHUNK_BYTES + if (crypto == null) 0 else SessionCrypto.OVERHEAD
        else -> throw IllegalArgumentException("Invalid frame kind")
    }

    companion object {
        const val KIND_MESSAGE: Byte = 1
        const val KIND_CHUNK: Byte = 2
        const val HEADER_BYTES = 5
        const val SYNC_ID_BYTES = 36
        const val MAX_FRAME_BYTES = 32 * 1024 * 1024
        const val CHUNK_BYTES = 256 * 1024
        internal val SAFE_ID = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
    }
}
