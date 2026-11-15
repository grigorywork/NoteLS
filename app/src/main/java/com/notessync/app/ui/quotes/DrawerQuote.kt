package com.notessync.app.ui.quotes

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notessync.app.settings.AppFont
import com.notessync.app.settings.DrawerQuoteAppearance
import com.notessync.app.ui.theme.appFontFamily

fun drawerQuoteText(custom: String, index: Int): String = custom.takeIf { it.isNotBlank() }
    ?: QuotesData.formatted(QuotesData.quotes.getOrNull(index) ?: QuotesData.quotes.first())

@Composable
fun DrawerQuoteCard(appearance: DrawerQuoteAppearance, index: Int = 0, modifier: Modifier = Modifier,
    textOverride: String? = null, maxLines: Int = Int.MAX_VALUE, onClick: (() -> Unit)? = null) {
    val style = appearance.safe()
    Card(colors = CardDefaults.cardColors(containerColor = if (style.backgroundColor == 0L)
        MaterialTheme.colorScheme.surfaceContainer else Color(style.backgroundColor)),
        modifier = modifier.fillMaxWidth().padding(12.dp).then(if (onClick != null)
            Modifier.clickable(role = Role.Button, onClickLabel = uiText("Открыть цитатник"), onClick = onClick) else Modifier)) {
        Text(textOverride ?: drawerQuoteText(style.customText, index),
            color = if (style.textColor == 0L) MaterialTheme.colorScheme.onSurface else Color(style.textColor),
            fontFamily = appFontFamily(style.font), fontSize = style.size.sp,
            lineHeight = (style.size * 1.4f).sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
    }
}

private val colors = listOf("Тема" to 0L, "Белый" to 0xFFFFFFFFL, "Тёмный" to 0xFF202124L,
    "Красный" to 0xFFB71C1CL, "Оранжевый" to 0xFFAD4B00L, "Жёлтый" to 0xFFFFD54FL,
    "Зелёный" to 0xFF1B5E20L, "Синий" to 0xFF1565C0L, "Фиолетовый" to 0xFF6A1B9AL)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorChoices(value: Long, onChange: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        colors.forEach { (label, color) -> FilterChip(value == color, { onChange(color) },
            label = { Text(uiText(label)) }, leadingIcon = { Box(Modifier.size(12.dp).background(
                if (color == 0L) MaterialTheme.colorScheme.primary else Color(color))) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawerQuoteDialog(initial: DrawerQuoteAppearance, onSave: (DrawerQuoteAppearance) -> Unit, onDismiss: () -> Unit, pinnedText: String? = null, busy: Boolean = false, problem: String? = null) {
    var enabled by rememberSaveable { mutableStateOf(initial.enabled) }
    var text by rememberSaveable { mutableStateOf(initial.customText) }
    var fontName by rememberSaveable { mutableStateOf(initial.font.name) }
    var size by rememberSaveable { mutableFloatStateOf(initial.safe().size) }
    var foreground by rememberSaveable { mutableLongStateOf(initial.safe().textColor) }
    var background by rememberSaveable { mutableLongStateOf(initial.safe().backgroundColor) }
    val appearance = DrawerQuoteAppearance(enabled, text, AppFont.valueOf(fontName), size, foreground, background)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(uiText("Цитата в меню")) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(uiText("Показывать цитату"), Modifier.weight(1f)); Switch(enabled, { enabled = it })
            }
            OutlinedTextField(text, { text = it }, label = { Text(uiText("Свой текст")) }, minLines = 2,
                placeholder = { Text(uiText("Пусто — случайная цитата")) }, isError = text.length > 3000,
                supportingText = { Text("${text.length}/3000") })
            TextButton(onClick = { text = "" }) { Text(uiText("Использовать случайную цитату")) }
            Text(uiText("Шрифт"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppFont.entries.forEach { font -> FilterChip(fontName == font.name, { fontName = font.name }, label = { Text(font.label) }) }
            }
            Text(uiText("Размер: %1\$s", (size.toInt()))); Slider(size, { size = it }, valueRange = 12f..32f, steps = 19)
            Text(uiText("Цвет текста")); ColorChoices(foreground) { foreground = it }
            Text(uiText("Фон")); ColorChoices(background) { background = it }
            if (pinnedText != null && text == initial.customText) Text(uiText("Закреплена цитата из цитатника. Изменение своего текста снимет закрепление."), style = MaterialTheme.typography.bodySmall)
            DrawerQuoteCard(appearance, textOverride = pinnedText.takeIf { text == initial.customText })
            val fg = if (foreground == 0L) MaterialTheme.colorScheme.onSurface else Color(foreground)
            val bg = if (background == 0L) MaterialTheme.colorScheme.surfaceContainer else Color(background)
            if ((maxOf(fg.luminance(), bg.luminance()) + 0.05f) / (minOf(fg.luminance(), bg.luminance()) + 0.05f) < 3f)
                Text(uiText("Низкий контраст: текст может быть плохо виден"), color = MaterialTheme.colorScheme.error)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { fontName = AppFont.SYSTEM.name; size = 16f; foreground = 0L; background = 0L }) { Text(uiText("Сбросить оформление")) }
        }
    }, confirmButton = { TextButton(enabled = !busy && text.length <= 3000, onClick = { onSave(appearance.safe()) }) { Text(uiText("Сохранить")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(uiText("Отмена")) } })
}
