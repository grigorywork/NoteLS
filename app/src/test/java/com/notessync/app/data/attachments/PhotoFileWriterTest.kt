package com.notessync.app.data.attachments

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class PhotoFileWriterTest {
    @Test
    fun writesPhotoToPersistentFile() {
        val directory = Files.createTempDirectory("photo-store").toFile()
        val destination = File(directory, "photo")
        val bytes = ByteArray(100_000) { (it % 251).toByte() }

        val size = PhotoFileWriter(MAX_PHOTO_BYTES).write(ByteArrayInputStream(bytes), destination)

        assertEquals(bytes.size.toLong(), size)
        assertTrue(destination.isFile)
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse(File(directory, "photo.pending").exists())
    }

    @Test(expected = PhotoTooLargeException::class)
    fun rejectsOversizedStreamAndRemovesPartialFile() {
        val directory = Files.createTempDirectory("photo-limit").toFile()
        val destination = File(directory, "photo")
        val input = GeneratedInputStream(MAX_PHOTO_BYTES + 1)

        try {
            PhotoFileWriter(MAX_PHOTO_BYTES).write(input, destination)
        } finally {
            assertFalse(destination.exists())
            assertFalse(File(directory, "photo.pending").exists())
        }
    }

    @Test(expected = IOException::class)
    fun removesPartialFileAfterReadFailure() {
        val directory = Files.createTempDirectory("photo-error").toFile()
        val destination = File(directory, "photo")
        val input = object : InputStream() {
            override fun read(): Int {
                throw IOException("Read failed")
            }
        }

        try {
            PhotoFileWriter(MAX_PHOTO_BYTES).write(input, destination)
        } finally {
            assertFalse(destination.exists())
            assertFalse(File(directory, "photo.pending").exists())
        }
    }

    @Test
    fun failedReplacementPreservesExistingOriginal() {
        val destination = File(Files.createTempDirectory("photo-existing").toFile(), "photo")
        val original = byteArrayOf(1, 2, 3)
        destination.writeBytes(original)
        val input = object : InputStream() { override fun read(): Int = throw IOException("failed") }
        try { PhotoFileWriter(MAX_PHOTO_BYTES).write(input, destination); throw AssertionError("expected failure") }
        catch (_: IOException) { assertArrayEquals(original, destination.readBytes()) }
        assertFalse(File(destination.parentFile, "photo.pending").exists())
    }

    private class GeneratedInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            remaining--
            return 0x41
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            java.util.Arrays.fill(buffer, offset, offset + count, 0x41.toByte())
            remaining -= count
            return count
        }
    }
}
