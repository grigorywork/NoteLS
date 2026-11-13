package com.notessync.app.sync

import com.notessync.app.sync.core.Hashing
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class HashingLimitTest {
    @Test fun `hashing accepts exact limit handles a zero read and refuses a growing input beyond limit`() {
        val bytes = byteArrayOf(1, 2, 3)
        val zeroFirst = object : ByteArrayInputStream(bytes) {
            var first = true
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(buffer, offset, length)
            }
        }
        assertEquals(Hashing.sha256Hex(bytes), Hashing.sha256Hex(zeroFirst, 3))
        assertThrows(IllegalArgumentException::class.java) { Hashing.sha256Hex(bytes.inputStream(), 2) }
        assertEquals(Hashing.sha256Hex(ByteArray(0)), Hashing.sha256Hex(ByteArray(0).inputStream(), 0))
    }
}
