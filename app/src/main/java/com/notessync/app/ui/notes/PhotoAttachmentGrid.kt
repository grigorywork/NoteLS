package com.notessync.app.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.notes.photos.PhotoDisplaySize
import com.notessync.app.ui.notes.photos.PhotoBitmapState
import com.notessync.app.ui.notes.photos.rememberPhotoBitmap

@Composable
fun PhotoAttachmentGrid(attachments: List<AttachmentEntity>, onOpen: (AttachmentEntity) -> Unit,
    onRemove: (AttachmentEntity) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        items(attachments, key = { it.id }) { photo -> PhotoThumbnail(photo, { onOpen(photo) }, { onRemove(photo) }) }
    }
}

@Composable
private fun PhotoThumbnail(attachment: AttachmentEntity, onOpen: () -> Unit, onRemove: () -> Unit) {
    val image = rememberPhotoBitmap(attachment, 512)
    val size = PhotoDisplaySize(attachment.displayWidthDp, attachment.displayHeightDp).bounded()
    val scale = 160f / maxOf(size.width, size.height)
    val width = (size.width * scale).coerceAtLeast(64f).dp
    val height = (size.height * scale).coerceAtLeast(64f).dp
    val shape = RoundedCornerShape(12.dp)
    Box(Modifier.padding(4.dp)) {
        Surface(Modifier.size(width, height).clip(shape).clickable(onClick = onOpen), shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant) {
            if (image is PhotoBitmapState.Ready) Image(image.bitmap.asImageBitmap(), "Фото заметки",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Default.Image, "Фото заметки", Modifier.padding(16.dp))
        }
        Surface(Modifier.align(Alignment.TopEnd), shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Удалить фото") }
        }
    }
}
