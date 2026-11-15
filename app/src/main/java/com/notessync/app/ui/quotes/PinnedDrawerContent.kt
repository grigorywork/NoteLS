package com.notessync.app.ui.quotes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.notessync.app.settings.DrawerQuoteAppearance

/** Footer and navigation have separate bounds; scrolling notebooks cannot move the quote. */
@Composable
internal fun PinnedDrawerContent(appearance: DrawerQuoteAppearance, index: Int, text: String?,
    showQuote: Boolean, onQuote: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("drawer-content")) {
        val oneLine = with(LocalDensity.current) { (appearance.safe().size * 1.4f).sp.toDp() } + 48.dp
        val desired = minOf(180.dp, maxOf(maxHeight * 0.35f, oneLine))
        val footerHeight = minOf(desired, (maxHeight - 96.dp).coerceAtLeast(48.dp))
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .testTag("drawer-navigation"), content = content)
            if (showQuote && appearance.enabled) DrawerQuoteCard(appearance, modifier = Modifier.heightIn(max = footerHeight).testTag("drawer-pinned-quote"),
                index = index, textOverride = text, maxLines = 4, onClick = onQuote)
        }
    }
}
