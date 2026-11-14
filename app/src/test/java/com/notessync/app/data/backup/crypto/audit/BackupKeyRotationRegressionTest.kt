package com.notessync.app.data.backup.crypto.audit

import com.notessync.app.data.backup.crypto.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Pure JCA fixture. A previously compromised archive must not decrypt future copies after password rotation. */
class BackupKeyRotationRegressionTest {
    @Test fun `old archive private key must not open new copies after password change`() {
        val oldPassword = "old fixture password".toCharArray()
        val newPassword = "new fixture password".toCharArray()
        val initial = BackupIdentity.create(oldPassword, 100_000)
        val oldFile = ByteArrayOutputStream().also { out ->
            BackupEncryptedIO.write(out, initial) { it.write("old fixture".toByteArray()) }
        }.toByteArray()
        val archivedIdentity = BackupEnvelopeCodec.read(oldFile.inputStream()).header.identity
        archivedIdentity.unlock(oldPassword).use { compromised ->
            initial.unlock(oldPassword).use { authorized ->
                val rotated = initial.changePassword(authorized, newPassword)
                val futureSecret = "NEW content created only after password rotation".toByteArray()
                val futureFile = ByteArrayOutputStream().also { out ->
                    BackupEncryptedIO.write(out, rotated) { it.write(futureSecret) }
                }.toByteArray()
                rotated.unlock(newPassword).use { current ->
                    assertArrayEquals(futureSecret, BackupEncryptedIO.read(futureFile.inputStream(), current) { it.readBytes() })
                }
                assertTrue("Old exported private key still decrypts future content", runCatching {
                    BackupEncryptedIO.read(futureFile.inputStream(), compromised) { it.readBytes() }
                }.isFailure)
            }
        }
    }
}
