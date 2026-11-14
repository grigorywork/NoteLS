package com.notessync.app.data.backup.crypto

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException

/** JCA interoperability/framing tests, not phone timing or hardware Keystore measurements. */
class EncryptedBackupStreamsTest {
    private val password = "тестовый пароль 😀 123".toCharArray()
    private val identity by lazy { BackupIdentity.create(password, 100_000) }
    private fun encrypt(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        BackupEncryptedIO.write(output, identity, 1000) { it.write(bytes) }
    }.toByteArray()
    private fun decrypt(bytes: ByteArray): ByteArray = identity.unlock(password).use { access ->
        bytes.inputStream().use { input ->
            val envelope = BackupEnvelopeCodec.read(input)
            EncryptedBackupStreams.Reader(input, envelope, access) {}.use { it.readBytes() }
        }
    }
    private fun headerEnd(bytes: ByteArray) = 12 + ByteBuffer.wrap(bytes, 8, 4).int
    private fun fails(bytes: ByteArray) { assertTrue(runCatching { decrypt(bytes) }.isFailure) }

    @Test fun `portable password roundtrip includes empty boundary and multiple records without plaintext`() {
        for (size in listOf(0, 1, 65535, 65536, 65537, 200003)) {
            val plain = ByteArray(size) { (it % 251).toByte() }
            val encrypted = encrypt(plain)
            assertArrayEquals(BackupEnvelopeCodec.MAGIC, encrypted.copyOf(8))
            assertArrayEquals(plain, decrypt(encrypted))
            val imported = BackupEnvelopeCodec.read(encrypted.inputStream()).header.identity
            imported.unlock(password).use { portable ->
                assertArrayEquals(plain, BackupEncryptedIO.read(encrypted.inputStream(), portable) { it.readBytes() })
            }
        }
    }
    @Test fun `wrong password closed access and unrelated identity cannot decrypt`() {
        val bytes = encrypt("secret body".toByteArray())
        assertTrue(runCatching { identity.unlock("wrong password".toCharArray()) }.isFailure)
        val lease = identity.unlock(password); lease.close()
        assertTrue(runCatching { BackupEncryptedIO.read(bytes.inputStream(), lease) { it.readBytes() } }.isFailure)
        BackupIdentity.create(password, 100_000).unlock(password).use { other ->
            assertTrue(runCatching { BackupEncryptedIO.read(bytes.inputStream(), other) { it.readBytes() } }.isFailure)
        }
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("secret body"))
    }
    @Test fun `password change rotates recipient while previous archives retain their old password`() {
        val bytes = encrypt(byteArrayOf(1, 2, 3))
        val nextPassword = "a different password".toCharArray()
        identity.unlock(password).use { lease ->
            val changed = identity.changePassword(lease, nextPassword)
            assertNotEquals(identity.id, changed.id)
            assertNotEquals(identity.publicKey, changed.publicKey)
            assertTrue(runCatching { changed.unlock(password) }.isFailure)
            changed.unlock(nextPassword).use { current ->
                assertTrue(runCatching { BackupEncryptedIO.read(bytes.inputStream(), current) { it.readBytes() } }.isFailure)
                val future = ByteArrayOutputStream().also { out -> BackupEncryptedIO.write(out, changed) { it.write(42) } }.toByteArray()
                assertArrayEquals(byteArrayOf(42), BackupEncryptedIO.read(future.inputStream(), current) { it.readBytes() })
                val wrapped = BackupIdentity.decode(BackupEnvelopeCodec.read(future.inputStream()).header.wrappedKey)
                assertEquals(32, current.unwrap(wrapped).also { it.fill(0) }.size)
                // Verify cryptographic separation, independently of the Reader's keyId guard.
                assertTrue(runCatching { lease.unwrap(wrapped) }.isFailure)
                assertTrue(runCatching { BackupEncryptedIO.read(future.inputStream(), lease) { it.readBytes() } }.isFailure)
            }
        }
        val previous = BackupEnvelopeCodec.read(bytes.inputStream()).header.identity
        assertTrue(runCatching { previous.unlock(nextPassword) }.isFailure)
        previous.unlock(password).use { assertArrayEquals(byteArrayOf(1, 2, 3), BackupEncryptedIO.read(bytes.inputStream(), it) { stream -> stream.readBytes() }) }
    }
    @Test fun `header edits ciphertext edits reordered replayed missing and extra records fail closed`() {
        val bytes = encrypt(ByteArray(150000) { 7 }); val start = headerEnd(bytes)
        fails(bytes.copyOf().apply { this[start + 9] = (this[start + 9].toInt() xor 1).toByte() })
        val changed = bytes.toString(Charsets.UTF_8).indexOf("1000")
        assertTrue(changed > 0)
        fails(bytes.copyOf().apply { this[changed + 3] = '1'.code.toByte() })
        for (end in listOf(0, 7, 11, start - 1, start, bytes.size - 1, bytes.size - 25)) fails(bytes.copyOf(end))
        fails(bytes + byteArrayOf(0))
        val recordSize = 9 + 65536 + 16
        fails(bytes.copyOfRange(0, start) + bytes.copyOfRange(start + recordSize, bytes.size))
        fails(bytes.copyOfRange(0, start + recordSize) + bytes.copyOfRange(start, bytes.size))
        fails(bytes.copyOf().apply { ByteBuffer.wrap(this, start + 5, 4).putInt(Int.MAX_VALUE) })
        fails(bytes.copyOf().apply { this[start] = 2 })
    }
    @Test fun `oversize headers and expensive KDF parameters reject before key derivation`() {
        assertTrue(runCatching { BackupEnvelopeCodec.read((BackupEnvelopeCodec.MAGIC + ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()).inputStream()) }.isFailure)
        for (rounds in listOf(0, 99999, 1000001, Int.MAX_VALUE)) {
            assertTrue(runCatching { identity.copy(rounds = rounds).unlock(password) }.isFailure)
        }
    }
    @Test fun `multi megabyte pipeline bounds record allocations and checks cancellation`() {
        val file = File.createTempFile("encrypted-stream", ".bin")
        try {
            var maxWrite = 0
            file.outputStream().use { raw ->
                val limited = object : FilterOutputStream(raw) {
                    override fun write(b: ByteArray, off: Int, len: Int) { maxWrite = maxOf(maxWrite, len); require(len <= 65552); out.write(b, off, len) }
                }
                BackupEncryptedIO.write(limited, identity, 1000) { out ->
                    val block = ByteArray(8192) { 42 }; repeat(640) { out.write(block) }
                }
            }
            assertTrue(maxWrite <= 65552)
            identity.unlock(password).use { lease ->
                file.inputStream().use { input -> BackupEncryptedIO.read(input, lease) { decrypted ->
                    val buffer = ByteArray(8192); var count = 0L
                    while (true) { val read = decrypted.read(buffer); if (read < 0) break; assertTrue(buffer.take(read).all { it == 42.toByte() }); count += read }
                    assertEquals(5L * 1024 * 1024, count)
                } }
            }
            val cancelled = ByteArrayOutputStream(); var checks = 0
            assertTrue(runCatching { BackupEncryptedIO.write(cancelled, identity, 1000, { if (++checks == 3) throw CancellationException() }) { it.write(ByteArray(200000)) } }.exceptionOrNull() is CancellationException)
            fails(cancelled.toByteArray())
            identity.unlock(password).use { lease ->
                var reads = 0
                assertTrue(runCatching { BackupEncryptedIO.read(file.inputStream(), lease, { if (++reads == 2) throw CancellationException() }) { it.readBytes() } }.exceptionOrNull() is CancellationException)
            }
        } finally { file.delete() }
    }
    @Test fun `ZIP parser closing before final tag cannot bypass encrypted end verification`() {
        val zip = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("x")); it.write(byteArrayOf(1,2,3)); it.closeEntry()
        } }.toByteArray()
        val encrypted = encrypt(zip).dropLast(1).toByteArray()
        identity.unlock(password).use { lease ->
            assertTrue(runCatching { BackupEncryptedIO.read(encrypted.inputStream(), lease) { input ->
                java.util.zip.ZipInputStream(input).use { stream -> assertNotNull(stream.nextEntry); stream.readBytes() }
            } }.isFailure)
        }
    }
}
