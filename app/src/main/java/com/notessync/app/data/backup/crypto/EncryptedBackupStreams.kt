package com.notessync.app.data.backup.crypto

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Independent 64KiB AEAD records avoid providers buffering a whole GCM archive before its final tag. */
internal object EncryptedBackupStreams {
    const val CHUNK = 64 * 1024
    const val MAX_PLAIN = 2L * 1024 * 1024 * 1024 + 64L * 1024 * 1024
    private fun cipher(mode: Int, key: ByteArray, envelope: BackupEnvelope, kind: Int, seq: Int, length: Int): Cipher {
        val iv = BackupIdentity.decode(envelope.header.nonce) + ByteBuffer.allocate(4).putInt(seq).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            updateAAD(envelope.aad + ByteBuffer.allocate(9).put(kind.toByte()).putInt(seq).putInt(length).array())
        }
    }
    class Writer(output: OutputStream, identity: BackupIdentity, createdAt: Long, private val checkCancelled: () -> Unit) : OutputStream() {
        private val output = DataOutputStream(output)
        private val key = BackupIdentity.random(32)
        private val envelope = BackupEnvelopeCodec.write(this.output, BackupHeader(identity, createdAt,
            BackupIdentity.encode(BackupIdentity.wrap(identity.public(), key)), BackupIdentity.encode(BackupIdentity.random(8))))
        private val buffer = ByteArray(CHUNK)
        private var used = 0; private var seq = 0; private var total = 0L; private var closed = false
        override fun write(value: Int) { val one = byteArrayOf(value.toByte()); write(one, 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            check(!closed); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            require(length.toLong() <= MAX_PLAIN - total); total += length
            var at = offset; var left = length
            while (left > 0) {
                checkCancelled(); val count = minOf(left, CHUNK - used)
                bytes.copyInto(buffer, used, at, at + count); used += count; at += count; left -= count
                if (used == CHUNK) emit(0)
            }
        }
        private fun emit(kind: Int) {
            checkCancelled(); val size = used + 16
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key, envelope, kind, seq, size).doFinal(buffer, 0, used)
            output.writeByte(kind); output.writeInt(seq++); output.writeInt(size); output.write(encrypted)
            buffer.fill(0); used = 0
        }
        override fun flush() { check(!closed); output.flush() }
        override fun close() {
            if (closed) return
            try { if (used > 0) emit(0); emit(1); output.flush() }
            finally { closed = true; buffer.fill(0); key.fill(0); output.close() }
        }
        fun abort() { if (!closed) { closed = true; buffer.fill(0); key.fill(0); output.close() } }
    }
    class Reader(input: InputStream, private val envelope: BackupEnvelope, access: BackupAccess,
        private val checkCancelled: () -> Unit) : InputStream() {
        private val input = DataInputStream(input)
        private val key = run { require(access.keyId == envelope.header.identity.id); access.unwrap(BackupIdentity.decode(envelope.header.wrappedKey)).also { require(it.size == 32) } }
        private var buffer = ByteArray(0); private var position = 0; private var seq = 0; private var total = 0L
        private var finished = false; private var closed = false
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            check(!closed); require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            if (position == buffer.size && !next()) return -1
            val count = minOf(length, buffer.size - position); buffer.copyInto(bytes, offset, position, position + count); position += count
            return count
        }
        private fun next(): Boolean {
            if (finished) return false
            checkCancelled(); buffer.fill(0)
            val kind = input.read(); if (kind < 0) throw EOFException("Encrypted backup has no final record")
            val index = input.readInt(); val size = input.readInt()
            require(index == seq && kind in 0..1 && size in 16..(CHUNK + 16) && (kind != 1 || size == 16))
            require(kind != 0 || size > 16)
            val encrypted = ByteArray(size); input.readFully(encrypted)
            buffer = cipher(Cipher.DECRYPT_MODE, key, envelope, kind, seq++, size).doFinal(encrypted); position = 0
            total += buffer.size; require(total <= MAX_PLAIN)
            if (kind == 1) { require(input.read() == -1) { "Trailing encrypted backup data" }; finished = true; return false }
            return true
        }
        fun verifyEnd() { val discard = ByteArray(CHUNK); try { while (read(discard) >= 0) Unit } finally { discard.fill(0) } }
        override fun close() { if (!closed) { closed = true; buffer.fill(0); key.fill(0); input.close() } }
    }
}
