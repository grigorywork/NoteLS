package com.notessync.app.ui.notes

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The keyboard reduces the whole editor, including its toolbar, exactly once. */
@Composable
internal fun EditorScaffold(
    snackbarHost: @Composable () -> Unit = {},
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit,
    imeInsets: WindowInsets = WindowInsets.ime,
    content: @Composable () -> Unit
) {
    Scaffold(
        modifier = Modifier.windowInsetsPadding(imeInsets),
        snackbarHost = snackbarHost,
        topBar = topBar,
        bottomBar = bottomBar
    ) { padding: PaddingValues ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            content()
        }
    }
}
