package com.notessync.app.data.attachments

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.notessync.app.ui.notes.photos.CameraCaptureContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraCaptureTest {
    private val app = RuntimeEnvironment.getApplication()
    private val store = CameraCaptureStore(app)

    @Before fun before() { registerCameraTestProvider(app) }

    @Test fun `full resolution contract grants only output URI and provider rejects unrelated cache`() {
        val token = store.create()
        try {
            val uri = store.uri(token)
            val intent = CameraCaptureContract().createIntent(app, uri)
            assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
            assertEquals(uri, intent.getParcelableExtra<android.net.Uri>(MediaStore.EXTRA_OUTPUT))
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals("content", uri.scheme)
            assertTrue(CameraCaptureContract().parseResult(android.app.Activity.RESULT_OK, null))
            assertFalse(CameraCaptureContract().parseResult(android.app.Activity.RESULT_CANCELED, null))
            try { FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", File(app.cacheDir, "private-secret")); fail() }
            catch (_: IllegalArgumentException) { }
        } finally { store.delete(token) }
    }

    @Test fun `copy preserves full original bytes and EXIF without allocating full photo`() {
        val token = store.create(); val destinationId = UUID.randomUUID().toString()
        val file = store.file(token)
        val bitmap = Bitmap.createBitmap(2400, 1800, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it)) }; bitmap.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val original = file.readBytes()
        val result = store.import(token, destinationId)
        try {
            assertArrayEquals(original, result.file.readBytes())
            assertEquals("image/jpeg", result.mimeType)
            assertEquals(original.size.toLong(), result.sizeBytes)
            assertEquals(6, ExifInterface(result.file).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        } finally { result.file.delete(); store.delete(token) }
    }

    @Test fun `empty invalid oversized and missing results leave no attachment`() {
        val token = store.create(); val id = UUID.randomUUID().toString()
        val target = File(app.filesDir, "attachments/$id")
        fun rejects() { try { store.import(token, id); fail() } catch (_: IOException) { }; assertFalse(target.exists()) }
        rejects()
        store.file(token).writeText("not a photo"); rejects()
        RandomAccessFile(store.file(token), "rw").use { it.setLength(MAX_PHOTO_BYTES + 1) }; rejects()
        store.delete(token); rejects()
        try { store.delete("../../outside"); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun `event cleanup removes only stale capture tokens and preserves recent files`() {
        val old = store.create(); val recent = store.create()
        val unrelated = File(store.file(old).parentFile, "unrelated")
        unrelated.writeText("keep")
        store.file(old).setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L)
        val next = store.create()
        try { assertFalse(store.file(old).exists()); assertTrue(store.file(recent).exists()); assertTrue(unrelated.exists()) }
        finally { store.delete(recent); store.delete(next); unrelated.delete() }
    }
}
