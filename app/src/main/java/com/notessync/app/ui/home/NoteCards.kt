package com.notessync.app.ui.home

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import com.notessync.app.security.biometrics.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.filled.PushPin
import com.notessync.app.ui.notes.noteCardContainer
import com.notessync.app.ui.notes.noteAccent
import com.notessync.app.ui.notes.NoteCardMenu
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.settings.NotesLayout
import com.notessync.app.notes.text.notePreviewText
import com.notessync.app.ui.notes.NoteGridCard
import com.notessync.app.ui.notes.NotePhotoPreview
import com.notessync.app.data.local.model.*
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.tasks.CountdownIndicator
import com.notessync.app.ui.tasks.TaskTimerIndicator
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.ui.theme.GlassPanelShape
import com.notessync.app.ui.theme.LocalGlassTheme
import com.notessync.app.ui.theme.glassBorder
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
private fun LockedTaskCard(onClick: () -> Unit, horizontalPadding: Dp = 16.dp) {
    val glass = LocalGlassTheme.current
    ElevatedCard(
        onClick = onClick,
        shape = if (glass.enabled) GlassPanelShape else CardDefaults.elevatedShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 6.dp)
            .glassBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 16.dp)
            )
            Column {
                Text(uiText("Защищённая задача"), style = MaterialTheme.typography.titleMedium)
                Text(
                    uiText("Введите пароль для открытия"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteCardEntry,
    showPriority: Boolean = true,
    photo: AttachmentEntity? = null,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleCompleted: () -> Unit,
    onToggleTimer: () -> Unit,
    onSelect: () -> Unit = {}, onPin: () -> Unit = {}, onRestore: () -> Unit = {},
    selecting: Boolean = false, selected: Boolean = false, active: Boolean = true, deadlineNow: Long = System.currentTimeMillis(), onProtection: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val glass = LocalGlassTheme.current
    val completedColor = taskColorOption(note.taskColor).completedContainer
    // Этап 35: акцент задачи — цвет виден в заголовке и галочке.
    val accent = noteAccent(note)
    val cardColors = when {
        !note.isTask -> CardDefaults.elevatedCardColors(containerColor = noteCardContainer(note), contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        note.isCompleted -> CardDefaults.elevatedCardColors(
            // Стекло: выполненная задача тоже полупрозрачная.
            containerColor = if (glass.enabled) completedColor.copy(alpha = 0.55f) else completedColor,
            contentColor = Color(0xFF1D1B20)
        )
        else -> CardDefaults.elevatedCardColors(
            containerColor = noteCardContainer(note),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    ElevatedCard(
        colors = cardColors,
        shape = if (glass.enabled) GlassPanelShape else CardDefaults.elevatedShape,
        modifier = Modifier
            .fillMaxWidth().testTag("note-card-${note.id}").combinedClickable(onClick = onClick, onLongClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .glassBorder()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (note.isTask) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) Checkbox(selected, { onSelect() }, modifier = Modifier.semantics { contentDescription = uiText("Выбрать: ") + notePreviewText(note.title, 128) })
            if (note.isTask && !selecting && !note.isDeleted) {
                Checkbox(
                    checked = note.isCompleted,
                    onCheckedChange = { onToggleCompleted() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = accent,
                        uncheckedColor = accent
                    )
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (note.isTask && showPriority && note.taskPriority != 0) {
                        Icon(androidx.compose.material.icons.Icons.Default.Flag,
                            contentDescription = com.notessync.app.ui.tasks.TaskPriority.from(note.taskPriority).label,
                            tint = com.notessync.app.ui.tasks.priorityColor(note.taskPriority), modifier = Modifier.padding(end = 4.dp))
                    }
                    if (note.isProtected) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = uiText("Защищено паролем"),
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    if (note.isPinned) Icon(Icons.Default.PushPin, uiText("Закреплено"), modifier = Modifier.padding(end = 4.dp))
                    if (note.isFavorite) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = uiText("Избранное"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Text(
                        text = notePreviewText(note.title, 256).ifBlank { uiText("Без названия") },
                        style = MaterialTheme.typography.titleMedium,
                        // Этап 35: активная задача — заголовок цветом задачи.
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.hasTables) Text(uiText("Таблица"), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (photo != null) NotePhotoPreview(photo, 120)
                if (note.content.isNotBlank()) {
                    Text(
                        // HF-1: в карточках списка тоже отрисованный текст, без ** и [ ].
                        text = remember(note.content, note.textFormat) { MarkdownLite.parse(notePreviewText(note.content), formatting = note.textFormat, clipFormatting = true) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (note.isTask) {
                    TaskTimerIndicator(
                        elapsedMillis = note.taskElapsedMillis,
                        startedAt = note.taskTimerStartedAt,
                        modifier = Modifier.padding(top = 4.dp),
                        contentColor = accent, animateIcon = true, active = active
                    )
                    if (note.taskCountdownMillis > 0L || note.taskCountdownEndAt != null) {
                        CountdownIndicator(
                            countdownMillis = note.taskCountdownMillis,
                            endAt = note.taskCountdownEndAt,
                            modifier = Modifier.padding(top = 4.dp),
                            animateIcon = true, contentColor = accent, active = active
                        )
                    }
                }
                if (note.isTask) TaskDeadlineLabel(note.taskDueAt, deadlineNow, note.isCompleted, accent)
                Text(
                    text = formatDateTime(note.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.65f)
                )
            }
            if (note.isTask && !selecting && !note.isDeleted) {
                IconButton(
                    onClick = onToggleTimer,
                    enabled = !note.isCompleted || note.taskTimerStartedAt != null
                ) {
                    Icon(
                        if (note.taskTimerStartedAt != null) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (note.taskTimerStartedAt != null) {
                            uiText("Поставить таймер на паузу")
                        } else {
                            uiText("Запустить таймер")
                        }
                    )
                }
            }
            NoteCardMenu(note, onDelete, onSelect, onPin, onRestore, onProtection)
        }
    }
}

/** One lazy state for both layouts preserves the same anchor across a lane-count change. */
@OptIn(ExperimentalFoundationApi::class)

@Composable
internal fun NotesCardsLayout(notes: List<NoteCardEntry>, locked: List<TaskUnlockTarget>, layout: NotesLayout,
    photos: Map<Long, AttachmentEntity>, showPriority: Boolean,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    onOpen: (NoteCardEntry) -> Unit, onUnlock: (TaskUnlockTarget) -> Unit, onDelete: (NoteCardEntry) -> Unit,
    onToggleCompleted: (NoteCardEntry) -> Unit, onToggleTimer: (NoteCardEntry) -> Unit,
    selected: Set<Long> = emptySet(), onSelect: (NoteCardEntry) -> Unit = {},
    onPin: (NoteCardEntry) -> Unit = {}, onRestore: (NoteCardEntry) -> Unit = {}, deadlineNow: Long = System.currentTimeMillis(),
    onProtection: (NoteCardEntry) -> Unit = {}
) {
    val grid = layout == NotesLayout.GRID
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (grid && maxWidth >= 280.dp) 2 else 1
        var previousColumns by rememberSaveable { mutableIntStateOf(columns) }
        var previousLayout by rememberSaveable { mutableStateOf(layout) }
        var anchor by rememberSaveable { mutableIntStateOf(state.firstVisibleItemIndex) }
        LaunchedEffect(layout, columns, state) {
            if (previousLayout != layout || previousColumns != columns) {
                state.scrollToItem(anchor.coerceIn(0, (notes.size + locked.size - 1).coerceAtLeast(0)), 0)
                previousLayout = layout
                previousColumns = columns
            }
            snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { anchor = it }
        }
        LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(columns), state = state,
            modifier = Modifier.fillMaxSize().testTag("notes-cards"),
            contentPadding = PaddingValues(start = if (grid) 16.dp else 0.dp, end = if (grid) 16.dp else 0.dp,
                top = if (grid) 6.dp else 0.dp, bottom = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = if (grid) 12.dp else 0.dp
        ) {
            items(locked, key = { "locked-${it.id}:${it.syncId}" }, span = { StaggeredGridItemSpan.FullLine }) { task ->
                LockedTaskCard({ onUnlock(task) }, horizontalPadding = if (grid) 0.dp else 16.dp)
            }
            items(notes, key = { "note-${it.id}:${it.syncId}" }, contentType = { if (grid) "grid" else "list" }) { note ->
                val active by remember(state, note.id) { derivedStateOf { state.layoutInfo.visibleItemsInfo.any { it.key == "note-${note.id}:${note.syncId}" } } }
                if (grid) NoteGridCard(note, photos[note.id], showPriority,
                    { onOpen(note) }, { onDelete(note) }, { onToggleCompleted(note) }, { onToggleTimer(note) },
                    { onSelect(note) }, { onPin(note) }, { onRestore(note) }, selected.isNotEmpty(), note.id in selected, active, deadlineNow, { onProtection(note) })
                else NoteCard(note, showPriority, photos[note.id], { onOpen(note) }, { onDelete(note) },
                    { onToggleCompleted(note) }, { onToggleTimer(note) }, { onSelect(note) }, { onPin(note) },
                    { onRestore(note) }, selected.isNotEmpty(), note.id in selected, active, deadlineNow, { onProtection(note) })
            }
        }
    }
}
