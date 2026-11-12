package com.notessync.app.notes.photos

import com.notessync.app.sync.core.Hashing
import org.junit.Assert.*
import org.junit.Test

class PhotoDisplaySizeTest {
    @Test fun `all edges and corners resize only their axes with bounded values`() {
        val start = PhotoDisplaySize(300, 300)
        PhotoHandle.entries.forEach { handle ->
            assertEquals(PhotoDisplaySize(300 + handle.x * 24, 300 + handle.y * 18), start.drag(handle, 24, 18))
            val huge = start.drag(handle, Int.MAX_VALUE, Int.MIN_VALUE)
            assertTrue(huge.width in PhotoDisplaySize.MIN..PhotoDisplaySize.MAX)
            assertTrue(huge.height in PhotoDisplaySize.MIN..PhotoDisplaySize.MAX)
        }
        assertEquals(PhotoDisplaySize(), PhotoDisplaySize(0, -1).bounded())
        assertEquals(PhotoDisplaySize(144, 720), PhotoDisplaySize(1, 50_000).bounded())
    }
    @Test fun `sampling bounds landscape portrait thin odd and extreme dimensions without overflow`() {
        for ((w, h) in listOf(4033 to 3025, 100 to 9001, 10000 to 10, 512 to 512, 1 to 1, Int.MAX_VALUE to Int.MAX_VALUE)) {
            val sample = photoSampleSize(w, h, 512)
            assertTrue(sample > 0); assertEquals(0, sample and (sample - 1))
            assertTrue((maxOf(w, h).toLong() + sample - 1) / sample <= 512)
        }
        assertTrue(photoSampleSize(Int.MAX_VALUE, 1, 1) > 0)
        assertEquals(1, photoSampleSize(0, 0, 512))
    }
    @Test fun `streamed hashing is compatible and bounds the read buffer`() {
        val bytes = ByteArray(2_000_000) { (it % 251).toByte() }
        var largest = 0
        val input = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { largest = maxOf(largest, len); return super.read(b, off, len) }
        }
        assertEquals(Hashing.sha256Hex(bytes), Hashing.sha256Hex(input))
        assertTrue(largest <= 32 * 1024)
    }
}
