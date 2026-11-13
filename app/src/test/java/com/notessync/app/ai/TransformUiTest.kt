package com.notessync.app.ai

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransformUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun before() { SettingsRepository.get(RuntimeEnvironment.getApplication()).setUiLanguage(UiLanguage.RUSSIAN) }
    @Test fun `UI sends only after the named send button and replaces only after reviewing the editable result`() {
        var state by mutableStateOf(TransformState()); var sent = ""; var applied = ""
        compose.setContent { MaterialTheme {
            TransformPreview("Original %1" + '$' + "s 😀", TransformConfig(true, AiProvider.OPENAI, "fixture-model"), state,
                { input, _ -> sent = input; state = TransformState(TransformPhase.RESULT, "Candidate") }, { applied = it }, {})
        } }
        compose.runOnIdle { assertEquals("", sent); assertEquals("", applied) }
        compose.onNodeWithText("Отправить в OpenAI").performClick()
        compose.runOnIdle { assertEquals("Original %1" + '$' + "s 😀", sent); assertEquals("", applied) }
        compose.onNodeWithText("Проверьте результат перед заменой.").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextReplacement("Reviewed result")
        compose.onNodeWithText("Заменить текст").performClick()
        compose.runOnIdle { assertEquals("Reviewed result", applied) }
    }
    @Test fun `busy preview can cancel and cannot send or apply while a fixture request is in progress`() {
        var dismissed = false; var actions = 0
        compose.setContent { MaterialTheme {
            TransformPreview("Original", TransformConfig(true), TransformState(TransformPhase.WORKING), { _, _ -> actions++ }, { actions++ }, { dismissed = true })
        } }
        compose.onNodeWithText("Преобразовать").assertIsNotEnabled()
        compose.onNodeWithText("Отменить").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(0, actions) }
    }
}
