package com.notessync.app.ui.notes.text

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.notes.text.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Virtual Compose layout, not physical-device resource measurements. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParagraphLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `formatting one paragraph does not add a blank line`() {
        val text = formattedPreview("one\ntwo", TextFormatting().apply(0, 3, FormatKind.ALIGN, "CENTER"))
        var layout: TextLayoutResult? = null
        compose.setContent { MaterialTheme { Text(text, Modifier.width(320.dp), onTextLayout = { layout = it }) } }
        compose.runOnIdle { assertEquals(2, layout!!.lineCount); assertEquals(4, layout!!.getLineStart(1)) }
    }
    @Test fun `empty and trailing paragraphs preserve visual line counts`() {
        val document = mutableStateOf(AnnotatedString("start")); var layout: TextLayoutResult? = null
        compose.setContent { MaterialTheme { Text(document.value, Modifier.width(320.dp), onTextLayout = { layout = it }) } }
        for (raw in listOf("one\n", "\none", "\n", "one\n\ntwo", "one\n\n")) {
            compose.runOnIdle { document.value = formattedPreview(raw, TextFormatting().apply(0, raw.length, FormatKind.ALIGN, "END")) }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(raw, raw.count { it == '\n' } + 1, layout!!.lineCount) }
        }
    }
}
