package com.notessync.app.ui.quotes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.CustomQuoteEntity
import com.notessync.app.data.quotes.*
import com.notessync.app.i18n.uiText

fun QuoteProblem.label() = uiText(when (this) {
    QuoteProblem.INVALID -> "Некорректная подборка. Нужен UTF-8 JSON или TXT; проверьте текст, автора и источник."
    QuoteProblem.FILE_TOO_LARGE -> "Файл цитат больше 2 МиБ."
    QuoteProblem.TOO_MANY -> "За один раз можно загрузить до 500 цитат."
    QuoteProblem.LIMIT -> "В цитатнике уже достигнут лимит 2000 цитат."
    QuoteProblem.DUPLICATE -> "Такая цитата с этим автором уже есть."
    QuoteProblem.STALE -> "Цитата изменилась. Откройте её заново."
    QuoteProblem.FILE -> "Не удалось открыть файл цитат."
    QuoteProblem.SAVE -> "Не удалось сохранить изменения цитатника."
})

@Composable
internal fun QuoteEditorDialog(initial: QuoteDraft, busy: Boolean, problem: QuoteProblem?, onSave: (QuoteDraft) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial.text) }
    var author by rememberSaveable { mutableStateOf(initial.author) }
    var source by rememberSaveable { mutableStateOf(initial.source) }
    val draft = QuoteDraft(text, author, source)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(uiText("Своя цитата")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { if (it.length <= 3000) text = it }, label = { Text(uiText("Текст цитаты")) }, minLines = 3, maxLines = 6,
                supportingText = { Text("${text.length}/3000") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(author, { if (it.length <= 160) author = it }, label = { Text(uiText("Автор (необязательно)")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(source, { if (it.length <= 240) source = it }, label = { Text(uiText("Источник (необязательно)")) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            problem?.let { Text(it.label(), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = !busy && runCatching { draft.normalized() }.isSuccess) { Text(uiText("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(uiText("Отмена")) } })
}

@Composable
internal fun QuoteImportDialog(preview: QuoteImportPreview, busy: Boolean, problem: QuoteProblem?, onImport: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(uiText("Предпросмотр цитат")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Новых: %1\$s. Дубликатов: %2\$s.", preview.newCount, preview.duplicates))
            Text(uiText("Дубликаты текста и автора будут пропущены. Существующие цитаты не изменятся."), style = MaterialTheme.typography.bodySmall)
            preview.entries.take(5).forEach { Text(it.formatted(), maxLines = 4, overflow = TextOverflow.Ellipsis) }
            if (preview.entries.size > 5) Text(uiText("Показаны первые 5 цитат."), style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it.label(), color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = onImport, enabled = !busy && preview.newCount > 0) { Text(uiText("Загрузить")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(uiText("Отмена")) } })
}

@Composable
internal fun CustomQuoteCard(quote: CustomQuoteEntity, pinned: Boolean, busy: Boolean, onCopy: () -> Unit, onShare: () -> Unit,
    onSaveToNote: () -> Unit, onPin: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) menu = false }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (pinned) Text(uiText("Закреплена в меню"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text(quote.draft().formatted(), style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onCopy, enabled = !busy) { Icon(Icons.Default.ContentCopy, uiText("Скопировать")) }
                IconButton(onClick = onShare, enabled = !busy) { Icon(Icons.Default.Share, uiText("Поделиться")) }
                IconButton(onClick = onSaveToNote, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.NoteAdd, uiText("Сохранить в заметки")) }
                Box {
                    IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, uiText("Действия с цитатой")) }
                    DropdownMenu(menu && !busy, { menu = false }) {
                        DropdownMenuItem(text = { Text(uiText(if (pinned) "Открепить от меню" else "Закрепить в меню")) }, onClick = { menu = false; onPin() })
                        DropdownMenuItem(text = { Text(uiText("Редактировать")) }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text(uiText("Удалить")) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}
