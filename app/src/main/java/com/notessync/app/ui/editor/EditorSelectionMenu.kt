package com.notessync.app.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsState
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.text.*
import kotlin.math.roundToInt

@Stable
internal class EditorSelectionToolbar : TextToolbar {
    var rect by mutableStateOf<Rect?>(null); private set
    var copy: (() -> Unit)? = null; private set
    var paste: (() -> Unit)? = null; private set
    var cut: (() -> Unit)? = null; private set
    var all: (() -> Unit)? = null; private set
    override val status get() = if (rect == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
        copy = onCopyRequested; paste = onPasteRequested; cut = onCutRequested; all = onSelectAllRequested
        this.rect = rect
    }
    override fun hide() { rect = null; copy = null; paste = null; cut = null; all = null }
}

/** The selected text remains the anchor; clamp the popup into the currently visible window. */
internal class SelectionMenuPosition(private val rect: Rect, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection,
        popupContentSize: IntSize): IntOffset {
        val x = (rect.center.x.roundToInt() - popupContentSize.width / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = rect.top.roundToInt() - popupContentSize.height - gap
        val y = if (above >= 0) above else rect.bottom.roundToInt() + gap
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

@Composable
internal fun EditorSelectionMenu(toolbar: EditorSelectionToolbar, viewModel: NoteEditorViewModel,
    ui: EditorUiState, settings: SettingsState) {
    val held = ui.selectionRect.takeIf { ui.formatPopup?.anchor == FormatAnchor.CONTEXT }
    val rect = toolbar.rect ?: held ?: return
    if (viewModel.previewMode || viewModel.isClosing || viewModel.accessDenied || ui.modalOpen ||
        (ui.popup != null && ui.formatPopup?.anchor != FormatAnchor.CONTEXT)) return
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    fun open(menu: FormatMenu) {
        ui.selectionRect = rect
        ui.formatPopup = FormatPopup(FormatAnchor.CONTEXT, menu)
    }
    Popup(popupPositionProvider = remember(rect, gap) { SelectionMenuPosition(rect, gap) },
        onDismissRequest = { toolbar.hide(); ui.dismissMenus() }, properties = PopupProperties(focusable = false)) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
            Surface(shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) {
                Row(Modifier.widthIn(max = 320.dp).padding(horizontal = 4.dp)) {
                    toolbar.copy?.let { action -> IconButton(onClick = action, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.ContentCopy, uiText("Копировать"), Modifier.size(18.dp)) } }
                    toolbar.cut?.let { action -> IconButton(onClick = action, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.ContentCut, uiText("Вырезать"), Modifier.size(18.dp)) } }
                    toolbar.paste?.let { action -> IconButton(onClick = action, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.ContentPaste, uiText("Вставить", context = "clipboard"), Modifier.size(18.dp)) } }
                    toolbar.all?.let { action -> IconButton(onClick = action, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.SelectAll, uiText("Весь текст"), Modifier.size(18.dp)) } }
                    if (!viewModel.contentField.selection.collapsed) {
                    Box {
                        IconButton(onClick = { open(FormatMenu.FULL) }, modifier = Modifier.size(44.dp)) { Text("Aa", style = MaterialTheme.typography.bodyMedium) }
                        FormattingPopup(ui.formatPopup, FormatAnchor.CONTEXT, { ui.formatPopup = it }, viewModel.activeFormatting,
                            settings.advancedFormatting, activeEditorList(viewModel), editorFormattingActions(viewModel))
                    }
                    if (settings.advancedFormatting) {
                        IconButton(onClick = { open(FormatMenu.COLOR) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.FormatColorText, uiText("Цвет текста"), Modifier.size(18.dp)) }
                        IconButton(onClick = { open(FormatMenu.ALIGN) }, modifier = Modifier.size(44.dp)) { Icon(alignmentIcon(viewModel.activeFormatting[com.notessync.app.notes.text.FormatKind.ALIGN] ?: "START"), uiText("Выравнивание"), Modifier.size(18.dp)) }
                    }
                    }
                }
            }
        }
    }
}
