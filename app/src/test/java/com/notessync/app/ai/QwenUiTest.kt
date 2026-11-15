package com.notessync.app.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QwenUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    private lateinit var drawingView: View
    private class Keys : TransformKeyProvider {
        override fun key(create: Boolean): SecretKey = SecretKeySpec(ByteArray(32) { 19 }, "AES")
        override fun clear() = Unit
    }
    @Before fun before() {
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setAiProvider(AiProvider.OPENAI); setAiModel("fixture-openai-model"); setAiEnabled(true) }
        for (name in listOf(AndroidTransformSecrets.PREFS, AndroidTransformSecrets.GROQ_PREFS)) app.getSharedPreferences(name, 0).edit().clear().commit()
    }
    @After fun after() { settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setAiEnabled(false); setAiProvider(AiProvider.LOCAL_RULES); setAiModel("") } }
    @Test fun `Qwen names Groq as recipient waits for send and requires editable preview confirmation`() {
        var state by mutableStateOf(TransformState()); var sent = ""; var applied = ""
        compose.setContent { MaterialTheme {
            TransformPreview("Исходный текст", TransformConfig(true, AiProvider.GROQ_QWEN), state,
                { input, _ -> sent = input; state = TransformState(TransformPhase.RESULT, "Предложение Qwen") }, { applied = it }, {})
        } }
        compose.runOnIdle { assertEquals("", sent); assertEquals("", applied) }
        compose.onNodeWithText("Отправить в OpenAI").assertDoesNotExist()
        compose.onNodeWithText("Этот текст будет отправлен в Groq для Qwen. Бесплатно только на тарифе Free в пределах квоты; хранение — по условиям Groq.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Отправить в Groq").performClick()
        compose.runOnIdle { assertEquals("Исходный текст", sent); assertEquals("", applied) }
        compose.onNode(hasSetTextAction()).performTextReplacement("Проверенный результат")
        compose.onNodeWithText("Заменить текст").performClick()
        compose.runOnIdle { assertEquals("Проверенный результат", applied) }
    }
    @Test fun `provider switching clears unsubmitted key retains OpenAI model and deleting Groq leaves OpenAI vault intact`() = runBlocking {
        val open = AndroidTransformSecrets(app, Keys()); val groq = AndroidTransformSecrets(app, Keys(), AiProvider.GROQ_QWEN)
        open.save("fixture-openai-key"); groq.save("fixture-groq-key")
        compose.setContent { MaterialTheme {
            drawingView = LocalView.current
            val state by settings.state.collectAsState()
            androidx.compose.material3.Surface(Modifier.requiredSize(320.dp, 720.dp).testTag("qwen-settings-viewport")) {
                Column(Modifier.verticalScroll(rememberScrollState())) { TransformSettingsSection(state, settings) }
            }
        } }
        compose.onNodeWithText("Новый API-ключ OpenAI").performScrollTo().performTextInput("unsaved-fixture-key")
        compose.onNodeWithTag("settings-choice-ai-provider").performScrollTo().performClick()
        compose.onNodeWithText("Qwen · Groq").performClick()
        compose.onNodeWithText("Новый API-ключ Groq").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("Сохранить ключ").assertIsNotEnabled()
        compose.onNodeWithText("Модель OpenAI").assertDoesNotExist()
        compose.waitUntil { compose.onAllNodesWithText("Удалить ключ").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("settings-choice-ai-provider").performScrollTo()
        val bounds = compose.onNodeWithTag("qwen-settings-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top); drawingView.draw(canvas)
            File("build/reports/settings-preview/qwen-settings-320.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        compose.onNodeWithText("Удалить ключ").performScrollTo().performClick()
        compose.waitUntil { !app.getSharedPreferences(AndroidTransformSecrets.GROQ_PREFS, 0).contains("encrypted") }
        assertEquals("fixture-openai-key", open.read()); assertNull(groq.read())
        compose.onNodeWithTag("settings-choice-ai-provider").performScrollTo().performClick()
        compose.onNodeWithText("OpenAI").performClick()
        compose.onNodeWithText("Модель OpenAI").performScrollTo().assertTextContains("fixture-openai-model")
        compose.onNodeWithText("Сохранить ключ").performScrollTo().assertIsNotEnabled()
        assertEquals("fixture-openai-key", open.read()); assertEquals(AiProvider.OPENAI, settings.state.value.aiProvider)
    }
    @Test fun `Groq quota error keeps original and reaches settings without billing advice for OpenAI`() {
        var opened = 0; var sent = 0
        compose.setContent { MaterialTheme {
            TransformPreview("Сохранённый оригинал", TransformConfig(true, AiProvider.GROQ_QWEN), TransformState(problem = TransformProblem.BILLING),
                { _, _ -> sent++ }, {}, {}, onSettings = { opened++ })
        } }
        compose.onNodeWithText(TransformProblem.BILLING.label(AiProvider.GROQ_QWEN)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Сохранённый оригинал")
        compose.onNodeWithText("Настройки преобразователя").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened); assertEquals(0, sent) }
    }
    @Test fun `Groq settings explain Free account conditions in English without using OpenAI credentials`() {
        settings.apply { setUiLanguage(UiLanguage.ENGLISH); setAiProvider(AiProvider.GROQ_QWEN) }
        compose.setContent { MaterialTheme {
            val state by settings.state.collectAsState()
            Column(Modifier.verticalScroll(rememberScrollState())) { TransformSettingsSection(state, settings) }
        } }
        compose.onNodeWithText("Provider").assertExists()
        compose.onNodeWithText("Qwen via Groq. Free only on the Free plan within its quota. The app cannot verify your account plan.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New Groq API key").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New OpenAI API key").assertDoesNotExist()
        compose.onNodeWithText("Save key").assertIsNotEnabled()
    }
}
