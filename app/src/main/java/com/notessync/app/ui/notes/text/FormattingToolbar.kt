package com.notessync.app.ui.notes.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText
import com.notessync.app.notes.text.FormatKind
import com.notessync.app.settings.AppFont
import com.notessync.app.ui.notes.md.MarkdownEdit

internal enum class FormatMenu { FULL, FONT, SIZE, COLOR, HIGHLIGHT, ALIGN, LIST }
internal enum class FormatAnchor { TOP, FONT, COLOR, HIGHLIGHT, ALIGN, LIST, CONTEXT }
internal data class FormatPopup(val anchor: FormatAnchor, val menu: FormatMenu)

internal class FormattingActions(
    val toggle: (FormatKind) -> Unit,
    val format: (FormatKind, String?) -> Unit,
    val heading: (Int) -> Unit,
    val list: (MarkdownEdit.ListKind) -> Unit,
    val align: (String) -> Unit,
    val indent: (Int) -> Unit,
    val selectAll: () -> Unit
)

/** Seven equal touch areas fit320dp. Menus never add another permanent toolbar row. */
@Composable
internal fun FormattingToolbar(
    active: Map<FormatKind, String?>, advanced: Boolean, activeList: MarkdownEdit.ListKind?,
    popup: FormatPopup?, onPopup: (FormatPopup?) -> Unit, actions: FormattingActions,
    enabled: Boolean, showTables: Boolean, onTable: () -> Unit, onInsert: () -> Unit
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FormatTool(Modifier.weight(1f), FormatAnchor.FONT, FormatMenu.FULL, popup, onPopup,
                        uiText("Оформление текста"), enabled, active, advanced, activeList, actions) {
                        Text("Aa", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        if (MaterialTheme.typography.labelSmall.fontSize.value <= 13f)
                            Text(active[FormatKind.SIZE] ?: "18", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    if (advanced) {
                        FormatTool(Modifier.weight(1f), FormatAnchor.COLOR, FormatMenu.COLOR, popup, onPopup,
                            uiText("Цвет текста"), enabled, active, advanced, activeList, actions) {
                            ColorTool(Icons.Default.FormatColorText, active[FormatKind.COLOR], MaterialTheme.colorScheme.primary)
                        }
                        FormatTool(Modifier.weight(1f), FormatAnchor.HIGHLIGHT, FormatMenu.HIGHLIGHT, popup, onPopup,
                            uiText("Подложка"), enabled, active, advanced, activeList, actions) {
                            ColorTool(Icons.Default.BorderColor, active[FormatKind.HIGHLIGHT], MaterialTheme.colorScheme.tertiary)
                        }
                        FormatTool(Modifier.weight(1f), FormatAnchor.ALIGN, FormatMenu.ALIGN, popup, onPopup,
                            uiText("Выравнивание"), enabled, active, advanced, activeList, actions,
                            isSelected = true) {
                            Icon(alignmentIcon(active[FormatKind.ALIGN] ?: "START"), null, Modifier.size(20.dp))
                        }
                    }
                    FormatTool(Modifier.weight(1f), FormatAnchor.LIST, FormatMenu.LIST, popup, onPopup,
                        uiText("Вид списка"), enabled, active, advanced, activeList, actions, activeList != null) {
                        when (activeList) {
                            MarkdownEdit.ListKind.NUMBER -> Icon(Icons.Default.FormatListNumbered, null, Modifier.size(20.dp))
                            MarkdownEdit.ListKind.LETTER -> Text(if (com.notessync.app.i18n.UiStrings.language == com.notessync.app.i18n.UiLanguage.RUSSIAN) "а≡" else "a≡", style = MaterialTheme.typography.bodyMedium)
                            MarkdownEdit.ListKind.CHECKLIST -> Icon(Icons.Default.Checklist, null, Modifier.size(20.dp))
                            else -> Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null, Modifier.size(20.dp))
                        }
                    }
                    if (showTables) BoxWithConstraints(Modifier.weight(1f)) {
                        val showCaption = maxWidth >= 48.dp && MaterialTheme.typography.labelSmall.fontSize.value <= 13f
                        IconButton(onClick = onTable, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.GridOn, uiText("Таблица"), Modifier.size(18.dp))
                                if (showCaption) Text(uiText("Таблица"), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                    IconButton(onClick = onInsert, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                        Icon(Icons.Default.Add, uiText("Вставить"), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatTool(modifier: Modifier, anchor: FormatAnchor, menu: FormatMenu,
    popup: FormatPopup?, onPopup: (FormatPopup?) -> Unit, label: String, enabled: Boolean,
    active: Map<FormatKind, String?>, advanced: Boolean, activeList: MarkdownEdit.ListKind?,
    actions: FormattingActions, isSelected: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Box(modifier) {
        IconButton(onClick = { onPopup(FormatPopup(anchor, menu)) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics {
                contentDescription = label; selected = isSelected
            }) {
            Row(Modifier.background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(8.dp)).padding(horizontal = 3.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
        FormattingPopup(popup, anchor, onPopup, active, advanced, activeList, actions)
    }
}

@Composable
private fun ColorTool(icon: ImageVector, hex: String?, fallback: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(20.dp))
        Box(Modifier.width(18.dp).height(2.dp).background(hexColor(hex) ?: fallback))
    }
}

internal fun alignmentIcon(value: String): ImageVector = when (value) {
    "CENTER" -> Icons.Default.FormatAlignCenter
    "END" -> Icons.AutoMirrored.Filled.FormatAlignRight
    "JUSTIFY" -> Icons.Default.FormatAlignJustify
    else -> Icons.AutoMirrored.Filled.FormatAlignLeft
}

private fun hexColor(value: String?): Color? = value?.toLongOrNull(16)?.let { Color(it) }
private val alignments = listOf("START" to "По левому краю", "CENTER" to "По центру", "END" to "По правому краю", "JUSTIFY" to "По ширине")
private val colors = listOf("По теме" to null, "Белый" to "FFFFFFFF", "Чёрный" to "FF202124", "Серый" to "FF757575",
    "Красный" to "FFE53935", "Оранжевый" to "FFFF9800", "Жёлтый" to "FFFFD54F", "Зелёный" to "FF43A047", "Синий" to "FF1E88E5", "Фиолетовый" to "FFAB47BC")

@Composable
internal fun FormattingPopup(popup: FormatPopup?, anchor: FormatAnchor, onPopup: (FormatPopup?) -> Unit,
    active: Map<FormatKind, String?>, advanced: Boolean, activeList: MarkdownEdit.ListKind?, actions: FormattingActions,
    cellMode: Boolean = false, mixedKinds: Set<FormatKind> = emptySet(), onReset: (() -> Unit)? = null) {
    DropdownMenu(expanded = popup?.anchor == anchor, onDismissRequest = { onPopup(null) }) {
        if (popup?.anchor == anchor) {
            fun close(action: () -> Unit) { action(); onPopup(null) }
            fun open(menu: FormatMenu) { onPopup(popup.copy(menu = menu)) }
            when (popup.menu) {
                FormatMenu.FULL -> {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        listOf(Triple(FormatKind.BOLD, Icons.Default.FormatBold, "Жирный"),
                            Triple(FormatKind.ITALIC, Icons.Default.FormatItalic, "Курсив"),
                            Triple(FormatKind.UNDERLINE, Icons.Default.FormatUnderlined, "Подчёркнутый"),
                            Triple(FormatKind.STRIKE, Icons.Default.StrikethroughS, "Зачёркнутый"))
                            .filter { advanced || it.first in listOf(FormatKind.BOLD, FormatKind.ITALIC) }.forEach { (kind, icon, label) ->
                                IconToggleButton(checked = active[kind] == "1", onCheckedChange = { actions.toggle(kind) }) {
                                    Icon(icon, uiText(label), Modifier.size(20.dp))
                                }
                            }
                    }
                    if (advanced) {
                        DropdownMenuItem(text = { Text(uiText("Размер") + ": " + (if (FormatKind.SIZE in mixedKinds) uiText("Разное оформление") else active[FormatKind.SIZE] ?: uiText("По теме"))) }, onClick = { open(FormatMenu.SIZE) })
                        DropdownMenuItem(text = { Text(uiText("Шрифт") + ": " + (if (FormatKind.FONT in mixedKinds) uiText("Разное оформление") else AppFont.entries.firstOrNull { it.name == active[FormatKind.FONT] }?.label ?: uiText("По теме"))) }, onClick = { open(FormatMenu.FONT) })
                        DropdownMenuItem(text = { Text(uiText("Цвет текста")) }, onClick = { open(FormatMenu.COLOR) })
                        DropdownMenuItem(text = { Text(uiText("Подложка")) }, onClick = { open(FormatMenu.HIGHLIGHT) })
                        DropdownMenuItem(text = { Text(uiText("Выравнивание")) }, leadingIcon = { Icon(alignmentIcon(active[FormatKind.ALIGN] ?: "START"), null) }, onClick = { open(FormatMenu.ALIGN) })
                    }
                    if (!cellMode) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        (1..3).forEach { level -> TextButton(onClick = { close { actions.heading(level) } }) { Text("H$level") } }
                    }
                    if (!cellMode) DropdownMenuItem(text = { Text(uiText("Вид списка")) }, onClick = { open(FormatMenu.LIST) })
                    if (advanced) Row(Modifier.padding(horizontal = 8.dp)) {
                        IconButton(onClick = { actions.indent(-1) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, uiText("Уменьшить отступ")) }
                        IconButton(onClick = { actions.indent(1) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, uiText("Увеличить отступ")) }
                        if (!cellMode) TextButton(onClick = { close(actions.selectAll) }) { Text(uiText("Весь текст")) }
                    }
                    if (onReset != null) DropdownMenuItem(text = { Text(uiText("Сбросить оформление")) },
                        onClick = { close(onReset) })
                    if (mixedKinds.isNotEmpty()) {
                        Text(uiText("Разное оформление"), Modifier.padding(12.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                FormatMenu.SIZE -> (listOf(uiText("По теме") to null) + listOf(12,14,16,18,20,24,28,32,40,48).map { "$it" to "$it" }).forEach { (label, value) ->
                    FormatOption(label, FormatKind.SIZE !in mixedKinds && active[FormatKind.SIZE] == value) { close { actions.format(FormatKind.SIZE, value) } }
                }
                FormatMenu.FONT -> (listOf(uiText("По теме") to null) + AppFont.entries.map { it.label to it.name }).forEach { (label, value) ->
                    FormatOption(label, FormatKind.FONT !in mixedKinds && active[FormatKind.FONT] == value) { close { actions.format(FormatKind.FONT, value) } }
                }
                FormatMenu.COLOR, FormatMenu.HIGHLIGHT -> {
                    val kind = if (popup.menu == FormatMenu.COLOR) FormatKind.COLOR else FormatKind.HIGHLIGHT
                    colors.forEach { (label, value) -> DropdownMenuItem(text = { Text(uiText(label)) },
                        leadingIcon = { hexColor(value)?.let { Box(Modifier.size(18.dp).background(it)) } },
                        trailingIcon = { if (kind !in mixedKinds && active[kind] == value) Icon(Icons.Default.Check, null) },
                        onClick = { close { actions.format(kind, value) } }) }
                }
                FormatMenu.ALIGN -> alignments.forEach { (value, label) ->
                    DropdownMenuItem(text = { Text(uiText(label)) }, leadingIcon = { Icon(alignmentIcon(value), null) },
                        trailingIcon = { if (FormatKind.ALIGN !in mixedKinds && (active[FormatKind.ALIGN] ?: "START") == value) Icon(Icons.Default.Check, null) },
                        onClick = { close { actions.align(value) } })
                }
                FormatMenu.LIST -> listOf(MarkdownEdit.ListKind.NUMBER to "Нумерованный", MarkdownEdit.ListKind.LETTER to "Буквенный",
                    MarkdownEdit.ListKind.BULLET to "Маркированный", MarkdownEdit.ListKind.CHECKLIST to "Чек-лист").forEach { (kind, label) ->
                    DropdownMenuItem(text = { Text(uiText(label)) }, leadingIcon = {
                        Text(when (kind) { MarkdownEdit.ListKind.NUMBER -> "1. 2. 3."; MarkdownEdit.ListKind.LETTER -> if (com.notessync.app.i18n.UiStrings.language == com.notessync.app.i18n.UiLanguage.RUSSIAN) "а) б) в)" else "a) b) c)"; MarkdownEdit.ListKind.BULLET -> "• • •"; else -> "☐" })
                    }, trailingIcon = { if (kind == activeList) Icon(Icons.Default.Check, null) },
                        onClick = { close { actions.list(kind) } })
                }
            }
        }
    }
}

@Composable
private fun FormatOption(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, modifier = Modifier.semantics { this.selected = selected }, trailingIcon = { if (selected) Icon(Icons.Default.Check, null) }, onClick = onClick)
}
