package com.notessync.app.ui.notes.text

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.notessync.app.notes.text.FormatKind
import com.notessync.app.settings.AppFont
import com.notessync.app.ui.notes.md.MarkdownEdit

@Composable
fun FormattingToolbar(active: Map<FormatKind, String?>, advanced: Boolean,
    onToggle: (FormatKind) -> Unit, onFormat: (FormatKind, String?) -> Unit,
    onHeading: (Int) -> Unit, onList: (MarkdownEdit.ListKind) -> Unit, onChecklist: () -> Unit,
    onAlign: (String) -> Unit, onIndent: (Int) -> Unit, onSelectAll: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            listOf(Triple(FormatKind.BOLD, Icons.Default.FormatBold, "Жирный"),
                Triple(FormatKind.ITALIC, Icons.Default.FormatItalic, "Курсив"),
                Triple(FormatKind.UNDERLINE, Icons.Default.FormatUnderlined, "Подчёркнутый"),
                Triple(FormatKind.STRIKE, Icons.Default.StrikethroughS, "Зачёркнутый"))
                .filter { advanced || it.first == FormatKind.BOLD || it.first == FormatKind.ITALIC }.forEach { (kind, icon, title) ->
                    IconToggleButton(active[kind] == "1", { onToggle(kind) }) { Icon(icon, title) }
                }
            var headings by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { headings = true }) { Icon(Icons.Default.Title, "Заголовки") }
                DropdownMenu(headings, { headings = false }) { (1..3).forEach { level ->
                    DropdownMenuItem(text = { Text("Заголовок $level") }, onClick = { onHeading(level); headings = false })
                } }
            }
            var lists by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { if (advanced) lists = true else onList(MarkdownEdit.ListKind.BULLET) }) {
                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Вид списка")
                }
                DropdownMenu(lists, { lists = false }) {
                    listOf(MarkdownEdit.ListKind.BULLET to "Маркеры", MarkdownEdit.ListKind.NUMBER to "Числа", MarkdownEdit.ListKind.LETTER to "Буквы").forEach { (kind, title) ->
                        DropdownMenuItem(text = { Text(title) }, onClick = { onList(kind); lists = false })
                    }
                }
            }
            IconButton(onClick = onChecklist) { Icon(Icons.Default.CheckBox, "Чек-лист") }
        }
        if (advanced) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            FormatMenu(active[FormatKind.SIZE] ?: "Размер", listOf("По теме" to null) +
                listOf(12, 14, 16, 18, 20, 24, 28, 32, 40, 48).map { "$it" to "$it" }) { onFormat(FormatKind.SIZE, it) }
            FormatMenu(AppFont.entries.firstOrNull { it.name == active[FormatKind.FONT] }?.label ?: "Шрифт",
                listOf("По теме" to null) + AppFont.entries.map { it.label to it.name }) { onFormat(FormatKind.FONT, it) }
            ColorMenu("Цвет", active[FormatKind.COLOR]) { onFormat(FormatKind.COLOR, it) }
            ColorMenu("Подложка", active[FormatKind.HIGHLIGHT]) { onFormat(FormatKind.HIGHLIGHT, it) }
            FormatMenu("Выравнивание", listOf("По левому краю" to "START", "По центру" to "CENTER", "По правому краю" to "END", "По ширине" to "JUSTIFY")) { if (it != null) onAlign(it) }
            IconButton(onClick = { onIndent(-1) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Уменьшить отступ") }
            IconButton(onClick = { onIndent(1) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Увеличить отступ") }
            TextButton(onClick = onSelectAll) { Text("Весь текст") }
        }
    }
}

@Composable
private fun FormatMenu(label: String, options: List<Pair<String, String?>>, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label) }
        DropdownMenu(open, { open = false }) { options.forEach { (title, value) ->
            DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(value); open = false })
        } }
    }
}
private val colors = listOf("По теме" to null, "Белый" to "FFFFFFFF", "Чёрный" to "FF202124", "Серый" to "FF757575",
    "Красный" to "FFE53935", "Оранжевый" to "FFFF9800", "Жёлтый" to "FFFFD54F", "Зелёный" to "FF43A047", "Синий" to "FF1E88E5", "Фиолетовый" to "FFAB47BC")
@Composable
private fun ColorMenu(label: String, current: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label)
            current?.let { Spacer(Modifier.width(4.dp)); Box(Modifier.size(12.dp).background(Color(it.toLong(16)))) }
        }
        DropdownMenu(open, { open = false }) { colors.forEach { (name, value) ->
            DropdownMenuItem(text = { Text(name) }, leadingIcon = { if (value != null) Box(Modifier.size(18.dp).background(Color(value.toLong(16)))) },
                onClick = { onSelect(value); open = false })
        } }
    }
}
