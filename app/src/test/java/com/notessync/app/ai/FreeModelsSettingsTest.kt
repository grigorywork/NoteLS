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
class FreeModelsSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun reset() { SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) } }
    @Before fun before() { reset(); app.getSharedPreferences("settings", 0).edit().clear().commit() }
    @After fun after() { SettingsRepository.get(app).apply { setAiEnabled(false); setAiProvider(AiProvider.LOCAL_RULES) }; reset() }
    @Test fun `four fixed free choices survive restart without enabling requests or changing the old OpenAI model`() {
        SettingsRepository.get(app).setAiModel("fixture-openai-model")
        val choices = AiProvider.entries.filter { it.fixedModel != null }
        assertEquals(4, choices.size)
        for (provider in choices) {
            SettingsRepository.get(app).setAiProvider(provider); reset()
            val restored = SettingsRepository.get(app).state.value
            assertFalse(restored.aiEnabled); assertEquals(provider, restored.aiProvider)
            assertEquals(provider.fixedModel, restored.transformConfig().model)
            assertEquals("fixture-openai-model", restored.aiModel)
        }
        SettingsRepository.get(app).setAiProvider(AiProvider.OPENAI)
        assertEquals("fixture-openai-model", SettingsRepository.get(app).state.value.transformConfig().model)
    }
}
