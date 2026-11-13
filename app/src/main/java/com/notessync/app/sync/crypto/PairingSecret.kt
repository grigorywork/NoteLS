package com.notessync.app.sync.crypto

import java.security.SecureRandom

/** A QR/manual invitation is a 256-bit random secret, never a human password. */
object PairingSecret {
    private val random = SecureRandom()
    private val hex = Regex("[0-9a-fA-F]{64}")
    fun valid(value: String): Boolean = hex.matches(value)
    fun generate(): String = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    internal fun bytes(value: String): ByteArray {
        require(valid(value)) { "Invalid pairing key" }
        return ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
