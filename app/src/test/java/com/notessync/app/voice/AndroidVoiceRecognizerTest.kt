package com.notessync.app.voice

import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.notessync.app.settings.VoiceEngine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, manifest = Config.NONE)
class AndroidVoiceRecognizerTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        ShadowSpeechRecognizer.reset()
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { name = "TestSpeechService"; packageName = "test.speech" }
        })
    }
    private class Results : VoiceInputManager.Listener {
        var state = VoiceState.IDLE; val finals = mutableListOf<String>(); val errors = mutableListOf<String>()
        override fun onState(state: VoiceState) { this.state = state }
        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) { finals += text }
        override fun onError(message: String) { errors += message }
    }
    private fun text(value: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value)) }
    @Test fun `online intent uses a language tag and release waits for final then destroys`() {
        val results = Results(); val manager = VoiceInputManager(app, results)
        assertTrue(manager.isAvailable(VoiceEngine.ONLINE))
        manager.start(VoiceEngine.ONLINE); shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        val intent = recognizer.lastRecognizerIntent
        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(Locale.getDefault().toLanguageTag(), intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertFalse(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 0))
        assertEquals(10_000L, intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0))
        assertEquals(10_000L, intent.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 0))
        recognizer.triggerOnReadyForSpeech(Bundle()); manager.finish()
        assertEquals(VoiceState.PROCESSING, results.state); assertFalse(recognizer.isDestroyed)
        recognizer.triggerOnResults(text("My dictation")); recognizer.triggerOnResults(text("Duplicate"))
        assertEquals(listOf("My dictation"), results.finals); assertTrue(recognizer.isDestroyed)
        assertEquals(VoiceState.IDLE, results.state); assertTrue(results.errors.isEmpty())
        manager.cancel()
    }
    @Test fun `unavailable local mode does not create a system recognizer on either API generation`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(false)
        val results = Results(); val manager = VoiceInputManager(app, results)
        assertFalse(manager.isAvailable(VoiceEngine.LOCAL)); manager.start(VoiceEngine.LOCAL)
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()); assertEquals(VoiceState.IDLE, results.state)
        assertEquals(1, results.errors.size); assertTrue(results.finals.isEmpty()); manager.cancel()
    }
    @Test fun `local mode is rejected below Android twelve and cancels late results when supported`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val results = Results(); val manager = VoiceInputManager(app, results)
        assertEquals(Build.VERSION.SDK_INT >= 31, manager.isAvailable(VoiceEngine.LOCAL))
        manager.start(VoiceEngine.LOCAL)
        if (Build.VERSION.SDK_INT < 31) {
            assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()); assertEquals(1, results.errors.size)
        } else {
            shadowOf(Looper.getMainLooper()).idle()
            val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            assertTrue(recognizer.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            recognizer.triggerOnReadyForSpeech(Bundle()); manager.cancel()
            assertTrue(recognizer.isDestroyed); recognizer.triggerOnResults(text("Do not insert"))
            assertTrue(results.finals.isEmpty()); assertTrue(results.errors.isEmpty()); assertEquals(VoiceState.IDLE, results.state)
        }
        manager.cancel()
    }
    @Test fun `explicit language and first nonblank result are forwarded without duplicate insertion`() {
        val results = Results(); val manager = VoiceInputManager(app, results) { "ru-RU" }
        manager.start(VoiceEngine.ONLINE); shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("ru-RU", recognizer.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        recognizer.triggerOnResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("  ", "Речь")) })
        recognizer.triggerOnResults(text("Duplicate"))
        assertEquals(listOf("Речь"), results.finals); assertTrue(results.errors.isEmpty()); assertTrue(recognizer.isDestroyed)
    }
    @Test fun `malformed result bundle becomes a recoverable no match and releases recognizer`() {
        val results = Results(); val manager = VoiceInputManager(app, results)
        manager.start(VoiceEngine.ONLINE); shadowOf(Looper.getMainLooper()).idle()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        manager.finish()
        recognizer.triggerOnResults(Bundle().apply { putInt(SpeechRecognizer.RESULTS_RECOGNITION, 42) })
        assertTrue(results.finals.isEmpty()); assertEquals(1, results.errors.size)
        assertTrue(recognizer.isDestroyed); assertEquals(VoiceState.IDLE, results.state)
    }
    @Test fun `provider phrase and silence rearm only the selected recognizer until deliberate stop`() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val engine = if (Build.VERSION.SDK_INT >= 31) VoiceEngine.LOCAL else VoiceEngine.ONLINE
        val results = Results(); val manager = VoiceInputManager(app, results) { "ru-RU" }
        manager.start(engine); shadowOf(Looper.getMainLooper()).idle()
        val firstRaw = ShadowSpeechRecognizer.getLatestSpeechRecognizer(); val first = shadowOf(firstRaw)
        first.triggerOnReadyForSpeech(Bundle()); first.triggerOnEndOfSpeech(); first.triggerOnResults(text("First phrase"))
        assertEquals(listOf("First phrase"), results.finals); assertEquals(VoiceState.RESTARTING, results.state)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        val secondRaw = ShadowSpeechRecognizer.getLatestSpeechRecognizer(); assertNotSame(firstRaw, secondRaw)
        val second = shadowOf(secondRaw); second.triggerOnReadyForSpeech(Bundle())
        assertEquals("ru-RU", second.lastRecognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertEquals(engine == VoiceEngine.LOCAL, second.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        second.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(VoiceState.RESTARTING, results.state); assertTrue(results.errors.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2001))
        val thirdRaw = ShadowSpeechRecognizer.getLatestSpeechRecognizer(); assertNotSame(secondRaw, thirdRaw)
        val third = shadowOf(thirdRaw)
        assertEquals(engine == VoiceEngine.LOCAL, third.lastRecognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        manager.finish(); third.triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(20))
        assertSame(thirdRaw, ShadowSpeechRecognizer.getLatestSpeechRecognizer()); assertTrue(third.isDestroyed)
        assertEquals(VoiceState.IDLE, results.state); assertEquals(listOf("First phrase"), results.finals); assertTrue(results.errors.isEmpty())
    }

}
