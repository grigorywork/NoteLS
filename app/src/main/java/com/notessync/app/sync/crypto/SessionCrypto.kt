package com.notessync.app.sync.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование сессии синхронизации: AES-256/GCM.
 * Ключ выводится из кода сессии (на Этапе 4 код будет зашит в QR-код).
 * GCM даёт и шифрование, и проверку целостности: подделанный кадр не расшифруется.
 */
class SessionCrypto(sessionCode: String) {

    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest(sessionCode.toByteArray(Charsets.UTF_8)),
        "AES"
    )

    /** Возвращает iv(12 байт) + шифротекст с тегом аутентификации. */
    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** Принимает iv(12 байт) + шифротекст. Бросает AEADBadTagException при подмене. */
    fun decrypt(data: ByteArray): ByteArray {
        require(data.size > GCM_IV_BYTES) { "Кадр слишком короткий" }
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(data.copyOfRange(GCM_IV_BYTES, data.size))
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
