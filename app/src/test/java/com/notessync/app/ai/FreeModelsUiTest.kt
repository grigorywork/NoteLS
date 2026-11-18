package com.notessync.app.ai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "en-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeModelsUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings get() = SettingsRepository.get(RuntimeEnvironment.getApplication())
    private class Secrets : TransformSecrets {
        override suspend fun read(): String? = null
        override suspend fun save(value: String) = Unit
        override suspend fun clear() = Unit
        override suspend fun present() = false
    }
    @Before fun before() { settings.apply { setUiLanguage(UiLanguage.ENGLISH); setAiEnabled(true); setAiProvider(AiProvider.LOCAL_RULES); setAiModel("fixture-openai-model") } }
    @After fun after() { settings.apply { setAiEnabled(false); setAiProvider(AiProvider.LOCAL_RULES); setUiLanguage(UiLanguage.RUSSIAN) } }
    @Test fun `four free choices show fixed models and the right credential form on a narrow screen`() {
        lateinit var view: View
        val secrets = Secrets()
        compose.setContent {
            val state by settings.state.collectAsState()
            view = LocalView.current
            NoteSyncTheme(darkTheme = false, dynamicColor = false) {
                Column(Modifier.requiredSize(320.dp, 640.dp).background(androidx.compose.material3.MaterialTheme.colorScheme.background)
                    .testTag("free-model-host").verticalScroll(rememberScrollState())) {
                    TransformSettingsSection(state, settings, secrets)
                }
            }
        }
        for (provider in AiProvider.entries.filter { it.fixedModel != null }) {
            compose.onNodeWithTag("settings-choice-ai-provider").performScrollTo().performClick()
            compose.onNode(hasText(provider.label) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
            compose.onNodeWithText(requireNotNull(provider.fixedModel)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(newTransformKeyLabel(provider)).performScrollTo().assertExists()
            compose.onNodeWithText(uiText("Модель OpenAI")).assertDoesNotExist()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
            compose.runOnIdle {
                assertEquals(provider, settings.state.value.aiProvider)
                assertEquals(provider.fixedModel, settings.state.value.transformConfig().model)
                assertEquals("fixture-openai-model", settings.state.value.aiModel)
            }
        }
        compose.onNodeWithTag("settings-choice-ai-provider").performScrollTo()
        val bounds = compose.onNodeWithTag("free-model-host").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top); view.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("The form preview must have an opaque theme background", pixels.all { android.graphics.Color.alpha(it) == 255 })
            val file = File("build/reports/settings-preview/free-model-nemotron-320.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
