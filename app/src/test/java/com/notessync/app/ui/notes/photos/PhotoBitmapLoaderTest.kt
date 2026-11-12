package com.notessync.app.ui.notes.photos

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoBitmapLoaderTest {
    @Test fun `real image decoding respects bound and original bytes stay unchanged`() {
        val file = File(Files.createTempDirectory("photo-decode").toFile(), "source.jpg")
        val source = Bitmap.createBitmap(1601, 901, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.RED)
        file.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 90, it)) }; source.recycle()
        val original = file.readBytes()
        val image = decodePhoto(file.path, 256)!!
        assertTrue(image.width <= 256 && image.height <= 256)
        assertArrayEquals(original, file.readBytes()); image.recycle()
        assertNull(decodePhoto(file.path + "-missing", 256))
        val bad = File(file.parentFile, "bad").apply { writeText("not an image") }
        assertNull(decodePhoto(bad.path, 256))
    }
    @Test fun `all eight EXIF orientations keep the correct corner and dimensions`() {
        val image = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.YELLOW)
        image.setPixels(colors, 0, 3, 0, 0, 3, 2)
        val topLeft = mapOf(1 to 0, 2 to 2, 3 to 5, 4 to 3, 5 to 0, 6 to 3, 7 to 5, 8 to 2)
        for (orientation in 1..8) {
            val rotated = orientPhoto(image, orientation)
            assertEquals("orientation $orientation", colors[topLeft.getValue(orientation)], rotated.getPixel(0, 0))
            assertEquals(if (orientation >= 5) 2 else 3, rotated.width)
            assertEquals(if (orientation >= 5) 3 else 2, rotated.height)
            if (rotated !== image) rotated.recycle()
        }
        image.recycle()
    }
    @Test fun `file EXIF flip is applied without rewriting the JPEG`() {
        val file = File(Files.createTempDirectory("photo-exif").toFile(), "source.jpg")
        val source = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (x in 0 until 40) for (y in 0 until 20) source.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 100, it) }; source.recycle()
        ExifInterface(file.path).apply { setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_FLIP_HORIZONTAL.toString()); saveAttributes() }
        val bytes = file.readBytes(); val result = decodePhoto(file.path, 256)!!
        assertTrue(Color.blue(result.getPixel(3, 10)) > Color.red(result.getPixel(3, 10)))
        assertArrayEquals(bytes, file.readBytes()); result.recycle()
    }
    @Test fun `only two decodes run and cancelled loads recycle unpublished images`() = runBlocking {
        val entered = CountDownLatch(2); val release = CountDownLatch(1)
        val active = AtomicInteger(0); val peak = AtomicInteger(0)
        val images = java.util.Collections.synchronizedList(mutableListOf<Bitmap>())
        val published = AtomicInteger(0)
        val loader = PhotoBitmapLoader { _, _ ->
            val n = active.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            active.decrementAndGet()
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also { images.add(it) }
        }
        val jobs = (0..7).map { launch(Dispatchers.Default) { loader.load("fake", 256) { published.incrementAndGet() } } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            jobs.forEach { it.cancel() }; release.countDown(); jobs.joinAll()
            assertEquals(2, peak.get()); assertEquals(2, images.size); assertEquals(0, published.get())
            assertTrue(images.all { it.isRecycled })
            loader.load("again", 256) { it?.recycle(); published.incrementAndGet() }
            assertEquals(1, published.get())
        } finally { release.countDown(); jobs.forEach { it.cancel() } }
    }
}
