package com.notessync.app.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

const val MIN_PASSWORD_LENGTH = 4
const val MAX_PASSWORD_LENGTH = 128

data class PasswordRecord(
    val hash: String,
    val salt: String
)

object PasswordProtector {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun create(password: String): PasswordRecord {
        require(password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) {
            "Password length is outside the allowed range"
        }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        return PasswordRecord(
            hash = Base64.getEncoder().encodeToString(derive(password, salt)),
            salt = Base64.getEncoder().encodeToString(salt)
        )
    }

    fun verify(password: String, record: PasswordRecord): Boolean = runCatching {
        val salt = Base64.getDecoder().decode(record.salt)
        val expected = Base64.getDecoder().decode(record.hash)
        MessageDigest.isEqual(expected, derive(password, salt))
    }.getOrDefault(false)

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
