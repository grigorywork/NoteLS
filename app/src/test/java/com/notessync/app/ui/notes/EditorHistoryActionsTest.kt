package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.ui.screens.NoteEditorScreen
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.ui.notes.text.EditorHistoryActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorHistoryActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `full editor buttons restore text title and focused field`() {
        val store = ViewModelStore()
        val app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        try {
            compose.setContent {
                val vm = remember { NoteEditorViewModel(app, SavedStateHandle()).also { store.put("editor", it) } }
                MaterialTheme { NoteEditorScreen(onBack = {}, onDeleted = {}, viewModel = vm) }
            }
            val fields = compose.onAllNodes(hasSetTextAction())
            fields.onLast().performTextInput("Привет")
            compose.onNodeWithContentDescription("Отменить").performClick()
            fields.onLast().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            compose.onNodeWithContentDescription("Повторить").performClick()
            fields.onLast().assertTextEquals("Привет")
            fields.onFirst().performTextInput("Заголовок")
            compose.onNodeWithContentDescription("Отменить").performClick()
            fields.onFirst().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(""))).assertIsFocused()
            fields.onLast().assertTextEquals("Привет")
        } finally { store.clear(); AppDatabase.closeInstance() }
    }

    @Test fun `accessible actions follow history state and setting while toolbar respects IME`() {
        val enabled = mutableStateOf(true)
        val canUndo = mutableStateOf(false)
        val canRedo = mutableStateOf(false)
        var undos = 0; var redos = 0; var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                Box(Modifier.requiredSize(320.dp, 640.dp).testTag("viewport")) {
                    EditorScaffold(imeInsets = WindowInsets(bottom = (200 * density).toInt()),
                        topBar = {}, bottomBar = {
                            Column(Modifier.testTag("toolbar")) {
                                if (enabled.value) EditorHistoryActions(canUndo.value, canRedo.value,
                                    { undos++ }, { redos++ })
                                Box(Modifier.fillMaxWidth().height(80.dp))
                            }
                        }) { Box(Modifier.fillMaxSize().testTag("content")) }
                }
            }
        }
        compose.onNodeWithContentDescription("Отменить").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Повторить").assertIsNotEnabled()
        compose.runOnIdle { canUndo.value = true; canRedo.value = true }
        compose.onNodeWithContentDescription("Отменить").performClick()
        compose.onNodeWithContentDescription("Повторить").performClick()
        assertEquals(1, undos); assertEquals(1, redos)
        for (show in listOf(true, false)) {
            compose.runOnIdle { enabled.value = show }
            val toolbar = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
            val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
            assertEquals(viewport.bottom - 200 * density, toolbar.bottom, 1.5f)
            assertEquals(toolbar.top, content.bottom, 1.5f)
            assertTrue(content.height > 0)
        }
        compose.onNodeWithContentDescription("Отменить").assertDoesNotExist()
    }
}
