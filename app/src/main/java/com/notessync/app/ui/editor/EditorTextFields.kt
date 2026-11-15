package com.notessync.app.ui.editor

import com.notessync.app.i18n.uiText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.notessync.app.notes.history.EditorField
import com.notessync.app.ui.notes.text.HistoryInput
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.settings.CompletedStyle
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.notes.md.MarkdownVisualTransformation
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.settings.SettingsState
import androidx.compose.foundation.layout.ColumnScope

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ColumnScope.EditorTextFields(viewModel: NoteEditorViewModel, ui: EditorUiState, settingsState: SettingsState,
    modifier: Modifier, titleFocus: FocusRequester, contentFocus: FocusRequester,
    recordingBusy: Boolean, voiceState: com.notessync.app.voice.VoiceState, voiceClosing: Boolean,
    inputMinHeight: Dp = 160.dp, viewportHeight: Dp = inputMinHeight,
    selectionToolbar: androidx.compose.ui.platform.TextToolbar? = null) {
    val defaultToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
    if (viewModel.previewMode) {
        PreviewBlock(
            text = viewModel.contentField.text,
            formatting = viewModel.formatting,
            hideCompleted = settingsState.completedStyle == CompletedStyle.HIDE,
            onToggleCheckbox = viewModel::toggleCheckboxAtLine,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = inputMinHeight)
        )
    } else {
        HistoryInput(viewModel::prepareClipboardEdit) {
            BasicTextField(
                enabled = viewModel.isReady && !viewModel.isClosing,
                value = viewModel.titleField, onValueChange = viewModel::onTitleChange,
                textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.then(modifier).focusRequester(titleFocus)
                    .onFocusChanged { if (it.isFocused) viewModel.onEditorFocus(EditorField.TITLE) }
                    .fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("editor-title"),
                decorationBox = { inner -> Box {
                    if (viewModel.titleField.text.isEmpty()) Text(uiText(if (viewModel.isTask) "Название задачи" else "Заголовок"),
                        style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                } }
            )
        }

        val visualTransformation = remember(viewModel.formatting) {
            com.notessync.app.ui.notes.text.FormattedTextTransformation(viewModel.formatting)
        }
        var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        val caretVisibility = remember { BringIntoViewRequester() }
        var contentFocused by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        // Отступ текста задан явно в contentPadding DecorationBox ниже.
        val padStartPx = with(density) { 16.dp.toPx() }
        val padTopPx = with(density) { 16.dp.toPx() }
        val glyphZonePx = with(density) { 56.dp.toPx() }
        val editorInteractionSource = remember { MutableInteractionSource() }
        val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
        val cursorBox = editorLayout?.let { layout ->
            val offset = visualTransformation.filter(AnnotatedString(viewModel.contentField.text)).offsetMapping
                .originalToTransformed(viewModel.contentField.selection.end)
            layout.getCursorRect(offset.coerceIn(0, layout.layoutInput.text.length)).translate(padStartPx, padTopPx)
        }
        // Key by caret geometry, not TextLayoutResult identity: remeasurement must not request another scroll forever.
        LaunchedEffect(cursorBox, viewModel.contentField.selection, viewportHeight, contentFocused, windowFocused) {
            if (contentFocused && windowFocused && cursorBox != null) caretVisibility.bringIntoView(cursorBox)
        }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalTextToolbar provides (selectionToolbar ?: defaultToolbar)) {
        HistoryInput(viewModel::prepareClipboardEdit) {
            BasicTextField(
                enabled = viewModel.isReady && !viewModel.isClosing,
                value = viewModel.contentField,
                onValueChange = viewModel::onContentChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = MaterialTheme.typography.bodyLarge.fontSize * 1.125f,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                // Этап 27: маркеры скрыты всегда; стили вводятся кнопками-режимами.
                visualTransformation = visualTransformation,
                onTextLayout = { editorLayout = it },
                interactionSource = editorInteractionSource,
                modifier = Modifier.then(modifier).focusRequester(contentFocus)
                    .bringIntoViewRequester(caretVisibility)
                    .onFocusChanged {
                        contentFocused = it.isFocused
                        if (it.isFocused) viewModel.onEditorFocus(EditorField.CONTENT)
                    }
                    .fillMaxWidth().testTag("editor-body")
                    .heightIn(min = inputMinHeight)
                    // Тап по квадратику чекбокса переключает его, не двигая курсор.
                    .pointerInput(visualTransformation) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            val layout = editorLayout ?: return@awaitEachGesture
                            val source = viewModel.contentField.text
                            val position = Offset(
                                down.position.x - padStartPx,
                                down.position.y - padTopPx
                            )
                            val transformedOffset = layout.getOffsetForPosition(position)
                            val original = visualTransformation
                                .filter(AnnotatedString(source))
                                .offsetMapping.transformedToOriginal(transformedOffset)
                            val lineStart = source.lastIndexOf('\n', original - 1)
                                .let { if (it < 0) 0 else it + 1 }
                            val lineEnd = source.indexOf('\n', original)
                                .let { if (it < 0) source.length else it }
                            val line = source.substring(lineStart, lineEnd)
                            val isCheckbox = line.startsWith("- [ ] ") ||
                                line.startsWith("- [x] ") || line.startsWith("- [X] ")
                            val onMarker = original in lineStart until lineStart + 6
                            if (isCheckbox && (onMarker || position.x <= glyphZonePx)) {
                                down.consume()
                                waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                                val lineIndex =
                                    source.substring(0, lineStart).count { it == '\n' }
                                viewModel.toggleCheckboxAtLine(lineIndex)
                            }
                        }
                    },
                decorationBox = { innerTextField ->
                    TextFieldDefaults.DecorationBox(
                        value = viewModel.contentField.text,
                        innerTextField = innerTextField,
                        enabled = true,
                        singleLine = false,
                        visualTransformation = visualTransformation,
                        interactionSource = editorInteractionSource,
                        placeholder = {
                            Text(if (viewModel.isTask) uiText("Описание задачи") else uiText("Текст заметки"))
                        },
                        colors = borderlessColors(),
                        contentPadding = PaddingValues(16.dp)
                    )
                }
            )
        }
        }
    }
}
/**
 * Трансформация редактора: маркеры скрыты всегда (этап 27).
 * Стили вводятся кнопками-режимами, никаких звёздочек и скобок на экране.
 */
@Composable
private fun editorTransformation(): MarkdownVisualTransformation =
    remember { MarkdownVisualTransformation() }

/** Предпросмотр: markdown отрисован стилями; тап по чек-боксу переключает его. */
@Composable
private fun PreviewBlock(
    text: String,
    formatting: com.notessync.app.notes.text.TextFormatting,
    hideCompleted: Boolean,
    onToggleCheckbox: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val parsed = remember(text, formatting, hideCompleted) { com.notessync.app.ui.notes.text.formattedPreview(text, formatting, hideCompleted) }
    // Соответствие видимых строк строкам исходника (при скрытии выполненных).
    val sourceLines = remember(text, hideCompleted) {
        MarkdownLite.visibleSourceLines(text, hideCompleted)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    ClickableText(
        text = parsed,
        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        onTextLayout = { layout = it },
        onClick = { offset ->
            layout?.let { result ->
                val visibleLine = result.getLineForOffset(offset)
                val logicalLine = parsed.text.substring(0, result.getLineStart(visibleLine)).count { it == '\n' }
                val sourceLine = parsed.getStringAnnotations(com.notessync.app.ui.notes.text.SOURCE_LINE_ANNOTATION, offset, offset)
                    .lastOrNull()?.item?.toIntOrNull() ?: sourceLines.getOrElse(logicalLine) { logicalLine }
                onToggleCheckbox(sourceLine)
            }
        },
        modifier = modifier.padding(16.dp)
    )
}

/** Шаблоны обратного отсчёта (Этап 30). */

@Composable
private fun borderlessColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)
