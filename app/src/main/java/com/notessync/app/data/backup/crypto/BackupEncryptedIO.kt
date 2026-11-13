package com.notessync.app.data.backup.crypto

import java.io.BufferedInputStream
import java.io.FilterOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

internal object BackupEncryptedIO {
    fun encrypted(input: BufferedInputStream): Boolean {
        input.mark(8)
        val probe = ByteArray(8); var count = 0
        while (count < probe.size) {
            val read = input.read(probe, count, probe.size - count)
            if (read < 0) break
            if (read == 0) { val one = input.read(); if (one < 0) break; probe[count++] = one.toByte() } else count += read
        }
        input.reset()
        return count == probe.size && probe.contentEquals(BackupEnvelopeCodec.MAGIC)
    }
    fun <T> write(output: OutputStream, identity: BackupIdentity, createdAt: Long = System.currentTimeMillis(),
        checkCancelled: () -> Unit = {}, action: (OutputStream) -> T): T {
        identity.validate()
        val encrypted = EncryptedBackupStreams.Writer(object : FilterOutputStream(output) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
            override fun close() = flush() // Caller owns its descriptor and may fsync before atomic publication.
        }, identity, createdAt, checkCancelled)
        try { val value = action(encrypted); encrypted.close(); return value }
        catch (error: Throwable) { runCatching { encrypted.abort() }; throw error }
    }
    fun copy(input: InputStream, output: OutputStream, checkCancelled: () -> Unit) {
        val buffer = ByteArray(EncryptedBackupStreams.CHUNK)
        var total = 0L
        try {
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0)
                total += count
                require(total <= EncryptedBackupStreams.MAX_PLAIN + 2L * 1024 * 1024)
                output.write(buffer, 0, count)
            }
        } finally { buffer.fill(0) }
    }
    fun <T> read(input: InputStream, access: BackupAccess?, checkCancelled: () -> Unit = {}, action: (InputStream) -> T): T {
        val buffered = input.buffered()
        if (!encrypted(buffered)) return action(buffered)
        val envelope = BackupEnvelopeCodec.read(buffered)
        val unlocked = requireNotNull(access) { "Backup password or fingerprint required" }
        EncryptedBackupStreams.Reader(buffered, envelope, unlocked, checkCancelled).use { decrypted ->
            // ZIP readers close their input at central-directory EOF. Do not let that skip AEAD end verification.
            val value = action(object : FilterInputStream(decrypted) { override fun close() = Unit })
            decrypted.verifyEnd()
            return value
        }
    }
}
