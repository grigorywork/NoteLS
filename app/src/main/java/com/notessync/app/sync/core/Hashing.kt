package com.notessync.app.sync.core

import java.security.MessageDigest
import java.io.InputStream

object Hashing {
    fun sha256Hex(input: InputStream, maxBytes: Long = Long.MAX_VALUE, checkCancelled: () -> Unit = {}): String {
        require(maxBytes >= 0)
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(32 * 1024)
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val one = input.read()
                if (one < 0) break
                require(total < maxBytes) { "File too large" }
                digest.update(one.toByte()); total++
            } else {
                require(count.toLong() <= maxBytes - total) { "File too large" }
                digest.update(buffer, 0, count); total += count
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
