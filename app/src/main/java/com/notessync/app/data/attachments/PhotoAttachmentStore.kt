package com.notessync.app.data.attachments

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

const val MAX_PHOTO_BYTES: Long = 10L * 1024L * 1024L

data class StoredPhoto(
    val file: File,
    val mimeType: String,
    val sizeBytes: Long
)

class PhotoTooLargeException : IOException("Photo exceeds the size limit")

class PhotoAttachmentStore(context: Context) {
    private val contentResolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "attachments")
    private val writer = PhotoFileWriter(MAX_PHOTO_BYTES)

    fun import(uri: Uri, syncId: String): StoredPhoto {
        val mimeType = contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: throw IOException("The selected item is not an image")

        contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.length > MAX_PHOTO_BYTES) throw PhotoTooLargeException()
        }

        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open the selected image")
        val target = File(directory, syncId)
        val size = input.use { writer.write(it, target) }
        if (size <= 0L) {
            target.delete()
            throw IOException("The selected image is empty")
        }
        return StoredPhoto(target, mimeType, size)
    }
}

internal class PhotoFileWriter(private val maxBytes: Long) {
    fun write(input: InputStream, destination: File): Long {
        val directory = destination.parentFile
            ?: throw IOException("Attachment directory is missing")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create the attachment directory")
        }
        val pending = File(directory, destination.name + ".pending")
        pending.delete()
        try {
            val size = FileOutputStream(pending).use { output ->
                val written = copyWithLimit(input, output, maxBytes)
                output.fd.sync()
                written
            }
            if (!pending.renameTo(destination)) throw IOException("Cannot finalize the image")
            return size
        } catch (error: Exception) {
            pending.delete()
            throw error
        }
    }

    private fun copyWithLimit(input: InputStream, output: FileOutputStream, limit: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (total > limit - count) throw PhotoTooLargeException()
            output.write(buffer, 0, count)
            total += count
        }
        return total
    }
}
