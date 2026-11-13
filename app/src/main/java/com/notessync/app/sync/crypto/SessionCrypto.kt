package com.notessync.app.sync.crypto

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Per-connection, directional AES-256/GCM. Only an authenticated handshake creates it. */
class SessionCrypto private constructor(
    private val sendKey: SecretKeySpec,
    private val receiveKey: SecretKeySpec,
    private val sendPrefix: ByteArray,
    private val receivePrefix: ByteArray
) {
    private var sent = 0L
    private var received = 0L

    @Synchronized fun encrypt(plain: ByteArray, header: ByteArray): ByteArray {
        check(sent < Long.MAX_VALUE) { "Session exhausted" }
        val sequence = ByteBuffer.allocate(8).putLong(sent).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sendKey, GCMParameterSpec(128, sendPrefix + sequence))
        cipher.updateAAD(header + sequence)
        val result = sequence + cipher.doFinal(plain)
        sent++
        return result
    }

    @Synchronized fun decrypt(data: ByteArray, header: ByteArray): ByteArray {
        require(data.size >= OVERHEAD && received < Long.MAX_VALUE) { "Invalid encrypted frame" }
        val sequence = data.copyOfRange(0, 8)
        require(ByteBuffer.wrap(sequence).long == received) { "Invalid frame sequence" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, receiveKey, GCMParameterSpec(128, receivePrefix + sequence))
        cipher.updateAAD(header + sequence)
        val result = cipher.doFinal(data, 8, data.size - 8)
        received++
        return result
    }

    companion object {
        const val OVERHEAD = 8 + 16
        internal fun derive(root: ByteArray, client: Boolean): SessionCrypto {
            fun key(label: String, size: Int) = Hkdf.expand(root, "NoteLS3/$label".toByteArray(Charsets.US_ASCII), size)
            val c2s = key("client-key", 32)
            val s2c = key("server-key", 32)
            try {
                return SessionCrypto(
                    SecretKeySpec(if (client) c2s else s2c, "AES"),
                    SecretKeySpec(if (client) s2c else c2s, "AES"),
                    key(if (client) "client-iv" else "server-iv", 4),
                    key(if (client) "server-iv" else "client-iv", 4)
                )
            } finally { c2s.fill(0); s2c.fill(0) }
        }
    }
}
