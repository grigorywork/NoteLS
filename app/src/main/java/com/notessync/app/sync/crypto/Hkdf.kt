package com.notessync.app.sync.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5869, SHA-256; no passwords or short pairing codes. */
internal object Hkdf {
    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data)
    }
    fun extract(salt: ByteArray, input: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(32) else salt, input)
    fun expand(key: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        var block = 1
        while (offset < length) {
            previous = hmac(key, previous + info + byteArrayOf(block++.toByte()))
            val count = minOf(previous.size, length - offset)
            previous.copyInto(result, offset, 0, count)
            offset += count
        }
        previous.fill(0)
        return result
    }
}
