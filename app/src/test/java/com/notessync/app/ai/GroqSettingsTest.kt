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
class GroqSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun reset() { SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) } }
    @Before fun before() { reset(); app.getSharedPreferences("settings", 0).edit().clear().commit() }
    @After fun after() { SettingsRepository.get(app).apply { setAiEnabled(false); setAiProvider(AiProvider.LOCAL_RULES) }; reset() }
    @Test fun `explicit Qwen selection persists with a fixed model while the users OpenAI model remains unchanged`() {
        val settings = SettingsRepository.get(app)
        assertFalse(settings.state.value.aiEnabled); assertEquals(AiProvider.LOCAL_RULES, settings.state.value.aiProvider)
        settings.setAiModel("fixture-openai-model"); settings.setAiProvider(AiProvider.GROQ_QWEN)
        reset(); val restored = SettingsRepository.get(app)
        assertFalse(restored.state.value.aiEnabled)
        assertEquals(AiProvider.GROQ_QWEN, restored.state.value.aiProvider)
        assertEquals(GroqQwenTransport.MODEL, restored.state.value.transformConfig().model)
        assertEquals("fixture-openai-model", restored.state.value.aiModel)
        restored.setAiProvider(AiProvider.OPENAI)
        assertEquals("fixture-openai-model", restored.state.value.transformConfig().model)
    }
}
