package com.notessync.app.ai

import android.app.Application
import com.notessync.app.settings.SettingsRepository
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class TransformSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun reset() { SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) } }
    @Before fun before() { reset(); app.getSharedPreferences("settings", 0).edit().clear().commit() }
    @After fun after() { SettingsRepository.get(app).setAiEnabled(false); reset() }
    @Test fun `new install is off without credentials and explicit provider model preferences survive reloading`() {
        val settings = SettingsRepository.get(app)
        assertFalse(settings.state.value.aiEnabled); assertEquals(AiProvider.LOCAL_RULES, settings.state.value.aiProvider)
        settings.setAiEnabled(true); settings.setAiProvider(AiProvider.OPENAI); settings.setAiModel("fixture-model")
        reset(); val restored = SettingsRepository.get(app).state.value
        assertTrue(restored.aiEnabled); assertEquals(AiProvider.OPENAI, restored.aiProvider); assertEquals("fixture-model", restored.aiModel)
        assertFalse(app.getSharedPreferences(AndroidTransformSecrets.PREFS, 0).contains("encrypted"))
    }
    @Test fun `quick button preference survives recreation independently of provider and enabled state`() {
        val settings = SettingsRepository.get(app)
        assertTrue(settings.state.value.quickTransformButton)
        settings.setAiEnabled(true); settings.setAiProvider(AiProvider.GROQ_QWEN)
        settings.setQuickTransformButton(false)
        reset()
        val restored = SettingsRepository.get(app)
        assertFalse(restored.state.value.quickTransformButton)
        assertTrue(restored.state.value.aiEnabled)
        assertEquals(AiProvider.GROQ_QWEN, restored.state.value.aiProvider)
        restored.setAiEnabled(false); restored.setAiEnabled(true)
        assertFalse(restored.state.value.quickTransformButton)
    }
}
