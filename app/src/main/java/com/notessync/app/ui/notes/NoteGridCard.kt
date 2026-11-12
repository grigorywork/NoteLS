package com.notessync.app.ui.notes

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.photos.PhotoDisplaySize
import com.notessync.app.notes.text.notePreviewText
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.notes.photos.PhotoBitmapState
import com.notessync.app.ui.notes.photos.rememberPhotoBitmap
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.theme.*

@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NoteGridCard(note: NoteEntity, photo: AttachmentEntity?, showPriority: Boolean,
    onClick: () -> Unit, onDelete: () -> Unit, onToggleCompleted: () -> Unit, onToggleTimer: () -> Unit,
    onSelect: () -> Unit = {}, onPin: () -> Unit = {}, onRestore: () -> Unit = {},
    selecting: Boolean = false, selected: Boolean = false, active: Boolean = true
) {
    val accent = noteAccent(note)
    val glass = LocalGlassTheme.current
    val completed = taskColorOption(note.taskColor).completedContainer
    val colors = if (note.isTask && note.isCompleted) CardDefaults.cardColors(
        containerColor = if (glass.enabled) completed.copy(alpha = .55f) else completed,
        contentColor = androidx.compose.ui.graphics.Color(0xFF1D1B20)
    ) else CardDefaults.cardColors(containerColor = noteCardContainer(note),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    Card(colors = colors, shape = if (glass.enabled) GlassPanelShape else MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("note-card-${note.id}").combinedClickable(onClick = onClick, onLongClick = onSelect).glassBorder()) {
        Column(Modifier.padding(8.dp)) {
            if (selecting) Checkbox(selected, { onSelect() }, modifier = Modifier.semantics { contentDescription = uiText("Выбрать: ") + notePreviewText(note.title, 128) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(notePreviewText(note.title, 256).ifBlank { uiText("Без названия") }, Modifier.weight(1f), maxLines = 3,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
                    color = accent)
                NoteCardMenu(note, onDelete, onSelect, onPin, onRestore)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showPriority && note.isTask && note.taskPriority != 0) Icon(Icons.Default.Flag,
                    TaskPriority.from(note.taskPriority).label, tint = priorityColor(note.taskPriority), modifier = Modifier.size(18.dp))
                if (note.isPinned) Icon(Icons.Default.PushPin, uiText("Закреплено"), modifier = Modifier.size(18.dp))
                if (note.isFavorite) Icon(Icons.Default.Star, uiText("Избранное"), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                if (note.isProtected) Icon(Icons.Default.Lock, uiText("Защищено паролем"), modifier = Modifier.size(18.dp))
                if (note.tablesData.isNotEmpty()) Text(uiText("Таблица"), style = MaterialTheme.typography.labelSmall)
            }
            if (photo != null) NotePhotoPreview(photo, 180)
            if (note.content.isNotBlank()) Text(
                remember(note.content, note.textFormat) { MarkdownLite.parse(notePreviewText(note.content), formatting = note.textFormat) },
                style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 4.dp))
            if (note.isTask) {
                if (!selecting && !note.isDeleted) Row {
                    Checkbox(note.isCompleted, { onToggleCompleted() },
                        modifier = Modifier.semantics { contentDescription = (if (note.isCompleted) uiText("Снять выполнение: ") else uiText("Выполнить задачу: ")) + notePreviewText(note.title, 128).ifBlank { uiText("Без названия") } },
                        colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = accent))
                    IconButton(onClick = onToggleTimer, enabled = !note.isCompleted || note.taskTimerStartedAt != null) {
                        Icon(if (note.taskTimerStartedAt != null) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (note.taskTimerStartedAt != null) uiText("Поставить таймер на паузу") else uiText("Запустить таймер"))
                    }
                }
                TaskTimerIndicator(note.taskElapsedMillis, note.taskTimerStartedAt, contentColor = accent, animateIcon = true, active = active)
                if (note.taskCountdownMillis > 0 || note.taskCountdownEndAt != null)
                    CountdownIndicator(note.taskCountdownMillis, note.taskCountdownEndAt, animateIcon = true, contentColor = accent, active = active)
            }
            Text(formatDateTime(note.updatedAt), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun NotePhotoPreview(photo: AttachmentEntity, maxHeight: Int) {
    val size = PhotoDisplaySize(photo.displayWidthDp, photo.displayHeightDp).bounded()
    val state = rememberPhotoBitmap(photo, 512)
    Box(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.constrainWidth(240.dp.roundToPx())
        val wanted = (width.toFloat() * size.height / size.width).roundToInt().coerceIn(64.dp.roundToPx(), maxHeight.dp.roundToPx())
        val height = constraints.constrainHeight(wanted)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) { placeable.place(0, 0) }
    }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        when (state) {
            is PhotoBitmapState.Ready -> Image(remember(state.bitmap) { state.bitmap.asImageBitmap() }, uiText("Фото заметки"),
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            PhotoBitmapState.Loading -> Icon(Icons.Default.Image, uiText("Подготовка фото"))
            PhotoBitmapState.Unavailable -> Icon(Icons.Default.BrokenImage, uiText("Фото недоступно"))
        }
    }
}
