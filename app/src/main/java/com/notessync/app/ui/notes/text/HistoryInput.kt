package com.notessync.app.ui.notes.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar

/** A one-character context-menu paste/cut must be an atomic edit too. */
@Composable
fun HistoryInput(onClipboardEdit: () -> Unit, content: @Composable () -> Unit) {
    val delegate = LocalTextToolbar.current
    val currentEdit = rememberUpdatedState(onClipboardEdit)
    val toolbar = remember(delegate) {
        object : TextToolbar by delegate {
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                                  onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                fun atomic(action: (() -> Unit)?): (() -> Unit)? = action?.let { { currentEdit.value(); it() } }
                delegate.showMenu(rect, onCopyRequested, atomic(onPasteRequested), atomic(onCutRequested), onSelectAllRequested)
            }
        }
    }
    CompositionLocalProvider(LocalTextToolbar provides toolbar, content = content)
}
