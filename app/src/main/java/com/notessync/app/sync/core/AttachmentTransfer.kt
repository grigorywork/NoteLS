package com.notessync.app.sync.core

import com.notessync.app.sync.protocol.AttachmentDto
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A descriptor-sized source. The protocol reads a bounded chunk and closes it on every exit. */
class SyncAttachmentSource(val sizeBytes: Long, val input: InputStream) : Closeable {
    override fun close() = input.close()
}

interface SyncAttachmentSink : Closeable {
    val output: OutputStream
    /** Validate the complete size/hash before publishing any file or changing its database row. */
    suspend fun commit()
}

/** Optional capability: old/delegating data sources retain their original byte-array contract. */
interface StreamingSyncDataSource : SyncDataSource {
    suspend fun openAttachment(attachmentSyncId: String): SyncAttachmentSource?
    suspend fun receiveAttachment(meta: AttachmentDto): SyncAttachmentSink
}

internal suspend fun SyncDataSource.openTransferAttachment(id: String): SyncAttachmentSource? =
    if (this is StreamingSyncDataSource) openAttachment(id)
    else readAttachmentBytes(id)?.let { SyncAttachmentSource(it.size.toLong(), it.inputStream()) }

internal suspend fun SyncDataSource.receiveTransferAttachment(meta: AttachmentDto): SyncAttachmentSink {
    if (this is StreamingSyncDataSource) return receiveAttachment(meta)
    val bytes = java.io.ByteArrayOutputStream(meta.sizeBytes.coerceIn(0L, 64L * 1024).toInt())
    return VerifiedAttachmentSink(meta, bytes, publish = { writeAttachmentBytes(meta, bytes.toByteArray()) }, cleanup = { bytes.reset() })
}

/** The same verifier serves disk-backed Room writes and the compatibility in-memory adapter. */
internal class VerifiedAttachmentSink(
    private val meta: AttachmentDto,
    private val target: OutputStream,
    private val publish: suspend () -> Unit,
    private val cleanup: () -> Unit = {}
) : SyncAttachmentSink {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var count = 0L
    private var finishing = false
    private var closed = false
    init { SyncLimits.attachment(meta) }
    override val output = object : OutputStream() {
        override fun write(value: Int) {
            check(!closed && !finishing)
            require(count < meta.sizeBytes) { "Attachment exceeds declared size" }
            target.write(value); digest.update(value.toByte()); count++
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed && !finishing)
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            require(length.toLong() <= meta.sizeBytes - count) { "Attachment exceeds declared size" }
            target.write(bytes, offset, length); digest.update(bytes, offset, length); count += length
        }
        override fun flush() = target.flush()
    }
    override suspend fun commit() {
        check(!closed && !finishing); finishing = true
        require(count == meta.sizeBytes && digest.digest().joinToString("") { "%02x".format(it) } == meta.sha256) {
            "Invalid attachment data"
        }
        target.flush()
        publish()
    }
    override fun close() {
        if (closed) return
        closed = true
        try { target.close() } finally { cleanup() }
    }
}
