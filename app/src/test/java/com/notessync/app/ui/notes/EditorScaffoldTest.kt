package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Simulated Compose layout/insets, not a hardware keyboard or battery measurement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `toolbar stays above keyboard and content has no extra keyboard gap`() {
        val imeHeight = mutableIntStateOf(0)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 640.dp).testTag("viewport")) {
                    EditorScaffold(
                        imeInsets = WindowInsets(bottom = imeHeight.intValue),
                        topBar = { Box(Modifier.fillMaxWidth().height(56.dp)) },
                        bottomBar = { Box(Modifier.fillMaxWidth().height(80.dp).testTag("toolbar")) }
                    ) { Box(Modifier.fillMaxSize().imePadding().testTag("content")) }
                }
            }
        }
        val initial = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        for (height in listOf(180, 280, 0)) {
            compose.runOnIdle { imeHeight.intValue = (height * density).toInt() }
            compose.waitForIdle()
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val toolbar = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.bottom - imeHeight.intValue, toolbar.bottom, 1.5f)
            assertEquals(initial.height, toolbar.height, 1.5f)
            assertEquals(toolbar.top, content.bottom, 1.5f)
            assertTrue(content.height > 0f)
            assertTrue(toolbar.width > 0f)
        }
    }
}
