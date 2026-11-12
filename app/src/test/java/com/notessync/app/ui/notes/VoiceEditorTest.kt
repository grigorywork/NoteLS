package com.notessync.app.ui.notes

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.os.Bundle
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import com.notessync.app.ui.screens.NoteEditorScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceEditorTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private var vm: NoteEditorViewModel? = null
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle = registry
    }
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var code: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { code = requestCode }
    }
    private val owner = Owner(); private val registry = Registry()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ShadowSpeechRecognizer.reset()
        SettingsRepository.get(app).apply {
            setUiLanguage(UiLanguage.RUSSIAN); setVoiceEngine(VoiceEngine.ONLINE)
            setVoiceControl(VoiceControl.HOLD); setVoiceStructure(VoiceStructure.RAW); setBackupFrequency(BackupFrequency.MANUAL)
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(RecognitionService.SERVICE_INTERFACE), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { name = "TestSpeechService"; packageName = "test.speech" }
        })
        owner.registry.currentState = Lifecycle.State.RESUMED
    }
    @After fun after() {
        store.clear()
        vm?.let { editor ->
            val drained = AtomicBoolean(false)
            compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { editor.saveNow(force = true); drained.set(true) } }
            compose.waitUntil(5_000) { compose.waitForIdle(); drained.get() }
        }
        AppDatabase.closeInstance()
    }
    private fun content(): NoteEditorViewModel {
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "Voice", content = "Original")) }
        val editor = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { store.put("editor", it); vm = it }
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registry) {
            NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { NoteEditorScreen({}, {}, viewModel = editor) } }
        } }
        compose.waitUntil(10_000) { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); compose.waitForIdle(); editor.isReady }
        return editor
    }
    private fun result(value: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value)) }
    @Test fun `pausing the editor cancels microphone and rejects late text without restarting on resume`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val editor = content()
        compose.onNodeWithTag("voice-input").performTouchInput { down(center) }
        val raw = ShadowSpeechRecognizer.getLatestSpeechRecognizer(); assertNotNull(raw)
        val recognizer = shadowOf(raw)
        compose.runOnIdle { recognizer.triggerOnReadyForSpeech(Bundle()); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag("voice-input").performTouchInput { up() }
        compose.runOnIdle {
            assertTrue(recognizer.isDestroyed); recognizer.triggerOnResults(result("Late private text"))
            assertEquals("Original", editor.contentField.text)
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertSame(raw, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        }
    }
    @Test fun `permission result after release never starts recording until a fresh gesture`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        content()
        compose.onNodeWithTag("voice-input").performTouchInput { down(center) }
        compose.runOnIdle { assertNotNull(registry.code); owner.registry.currentState = Lifecycle.State.CREATED }
        compose.onNodeWithTag("voice-input").performTouchInput { up() }
        compose.runOnIdle {
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
            registry.dispatchResult(registry.code!!, true)
            assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.onNodeWithTag("voice-input").performTouchInput { down(center) }
        compose.runOnIdle { assertNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) }
        compose.onNodeWithTag("voice-input").performTouchInput { cancel() }
        compose.runOnIdle { assertTrue(shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer()).isDestroyed) }
    }
    @Test fun `back stops recognition before saving and disables new voice input while closing`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val editor = content()
        compose.onNodeWithTag("voice-input").performClick()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        compose.runOnIdle { recognizer.triggerOnReadyForSpeech(Bundle()) }
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithTag("voice-input").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(recognizer.isDestroyed)
            recognizer.triggerOnResults(result("Late closing text"))
            assertEquals("Original", editor.contentField.text)
        }
    }

}
