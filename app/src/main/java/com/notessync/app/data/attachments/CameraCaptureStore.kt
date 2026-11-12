package com.notessync.app.data.attachments

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.UUID

/** Short-lived originals; all calls belong on IO. No preview bitmap is allocated here. */
class CameraCaptureStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.cacheDir, "camera")

    fun create(): String {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create camera directory")
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.forEach { if (VALID.matches(it.name) && it.lastModified() < cutoff) it.delete() }
        val token = UUID.randomUUID().toString() + ".jpg"
        if (!file(token).createNewFile()) throw IOException("Cannot create camera file")
        return token
    }

    fun uri(token: String): Uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file(token))

    fun import(token: String, syncId: String): StoredPhoto {
        val source = file(token)
        if (!source.isFile || source.length() <= 0) throw IOException("Camera returned no photo")
        if (source.length() > MAX_PHOTO_BYTES) throw PhotoTooLargeException()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        val mime = bounds.outMimeType?.takeIf { it.startsWith("image/") }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mime == null) throw IOException("Invalid camera image")
        val destination = File(app.filesDir, "attachments/$syncId")
        val size = source.inputStream().use { PhotoFileWriter(MAX_PHOTO_BYTES).write(it, destination) }
        return StoredPhoto(destination, mime, size)
    }

    fun delete(token: String) { file(token).delete() }

    internal fun file(token: String): File {
        require(VALID.matches(token)) { "Invalid camera token" }
        return File(directory, token)
    }

    companion object { private val VALID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.jpg") }
}
