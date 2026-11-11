package com.notessync.app.ui.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoAttachmentGrid(
    attachments: List<AttachmentEntity>,
    onOpen: (AttachmentEntity) -> Unit,
    onRemove: (AttachmentEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 232.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        attachments.forEach { attachment ->
            PhotoThumbnail(
                attachment = attachment,
                onOpen = { onOpen(attachment) },
                onRemove = { onRemove(attachment) }
            )
        }
    }
}

@Composable
private fun PhotoThumbnail(
    attachment: AttachmentEntity,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    var thumbnail by remember(attachment.filePath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(attachment.filePath) {
        thumbnail = withContext(Dispatchers.IO) { decodeThumbnail(attachment.filePath) }
    }
    Box(modifier = Modifier.padding(4.dp)) {
        val shape = RoundedCornerShape(12.dp)
        val current = thumbnail
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = "Фото заметки",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(112.dp)
                    .clip(shape)
                    .clickable(onClick = onOpen)
            )
        } else {
            Surface(
                shape = shape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(112.dp)
                    .clip(shape)
                    .clickable(onClick = onOpen)
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = "Фото заметки",
                    modifier = Modifier.padding(36.dp)
                )
            }
        }
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Удалить фото")
            }
        }
    }
}

private fun decodeThumbnail(path: String, maxDimension: Int = 512): Bitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= maxDimension ||
            bounds.outHeight / (sampleSize * 2) >= maxDimension
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return@runCatching null
        rotateFromExif(path, decoded)
    }.getOrNull()
}

private fun rotateFromExif(path: String, bitmap: Bitmap): Bitmap {
    val rotation = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotation == 0f) return bitmap
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
        postRotate(rotation)
    }, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}
