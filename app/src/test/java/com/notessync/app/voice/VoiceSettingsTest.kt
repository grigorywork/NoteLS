package com.notessync.app.voice

import android.app.Application
import android.content.Context
import android.os.Looper
import com.notessync.app.settings.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class VoiceSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun resetInstance() { SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) } }
    @Before fun before() { resetInstance(); app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit() }
    @After fun after() { shadowOf(Looper.getMainLooper()).idle(); resetInstance() }
    @Test fun `hold is default and tap selection persists independently of engine and structure`() {
        val settings = SettingsRepository.get(app)
        assertEquals(VoiceControl.HOLD, settings.state.value.voiceControl)
        settings.setVoiceControl(VoiceControl.TAP); settings.setVoiceEngine(VoiceEngine.LOCAL); settings.setVoiceStructure(VoiceStructure.RAW)
        shadowOf(Looper.getMainLooper()).idle(); resetInstance()
        val restored = SettingsRepository.get(app).state.value
        assertEquals(VoiceControl.TAP, restored.voiceControl); assertEquals(VoiceEngine.LOCAL, restored.voiceEngine); assertEquals(VoiceStructure.RAW, restored.voiceStructure)
    }
    @Test fun `unknown control preference falls back to hold without changing speech mode`() {
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("voice_control", "damaged").putString("voice_engine", "LOCAL").commit()
        val state = SettingsRepository.get(app).state.value
        assertEquals(VoiceControl.HOLD, state.voiceControl); assertEquals(VoiceEngine.LOCAL, state.voiceEngine)
    }
}
