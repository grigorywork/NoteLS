package com.notessync.app.ui.notes.photos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.runtime.*
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.notes.photos.photoSampleSize
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

internal sealed interface PhotoBitmapState {
    data object Loading : PhotoBitmapState
    data object Unavailable : PhotoBitmapState
    data class Ready(val bitmap: Bitmap) : PhotoBitmapState
}

/** Two decodes globally; a cancelled load never hands a bitmap to a disposed UI. */
internal class PhotoBitmapLoader(private val decode: (String, Int) -> Bitmap? = ::decodePhoto) {
    private val permits = Semaphore(2)
    suspend fun load(path: String, limit: Int, publish: (Bitmap?) -> Unit) = permits.withPermit {
        val receiver = coroutineContext
        withContext(Dispatchers.IO) {
            ensureActive()
            val bitmap = decode(path, limit)
            val delivered = AtomicBoolean(false)
            try {
                ensureActive()
                withContext(receiver) {
                    ensureActive()
                    delivered.set(true)
                    publish(bitmap)
                }
            } finally {
                if (!delivered.get()) bitmap?.recycle()
            }
        }
    }
    companion object { val shared = PhotoBitmapLoader() }
}

@Composable
internal fun rememberPhotoBitmap(attachment: AttachmentEntity, limit: Int): PhotoBitmapState {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var state by remember(attachment.filePath, attachment.sizeBytes, attachment.previewRevision, limit) {
        mutableStateOf<PhotoBitmapState>(PhotoBitmapState.Loading)
    }
    LaunchedEffect(attachment.filePath, attachment.sizeBytes, attachment.previewRevision, limit, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (state !is PhotoBitmapState.Ready) PhotoBitmapLoader.shared.load(attachment.filePath, limit) {
                state = it?.let { bitmap -> PhotoBitmapState.Ready(bitmap) } ?: PhotoBitmapState.Unavailable
            }
            awaitCancellation()
        }
    }
    return state
}

internal fun decodePhoto(path: String, limit: Int): Bitmap? {
    var owned: Bitmap? = null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        owned = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = photoSampleSize(bounds.outWidth, bounds.outHeight, limit)
        }) ?: return null
        // Defend against codec rounding/unsupported sampling without exposing a large bitmap to UI.
        val largest = maxOf(owned.width, owned.height)
        if (largest > limit) {
            val resized = Bitmap.createScaledBitmap(owned, (owned.width.toDouble() * limit / largest).roundToInt().coerceAtLeast(1),
                (owned.height.toDouble() * limit / largest).roundToInt().coerceAtLeast(1), true)
            if (resized !== owned) owned.recycle()
            owned = resized
        }
        val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotated = orientPhoto(owned, orientation)
        if (rotated !== owned) owned.recycle()
        owned = null
        rotated
    } catch (_: Exception) { owned?.recycle(); null }
    catch (_: OutOfMemoryError) { owned?.recycle(); null }
}

internal fun orientPhoto(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
