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
            setEditorHistory(true); setVoiceLanguage(VoiceLanguage.SYSTEM); setVoiceControl(VoiceControl.HOLD); setVoiceStructure(VoiceStructure.RAW); setBackupFrequency(BackupFrequency.MANUAL)
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
    private val editorSlot = androidx.compose.runtime.mutableStateOf<NoteEditorViewModel?>(null)
    private val inset = androidx.compose.runtime.mutableIntStateOf(0)
    private var density = 1f
    private lateinit var drawingView: android.view.View
    private fun content(initialContent: String = "Original"): NoteEditorViewModel {
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "Voice", content = initialContent)) }
        val editor = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { store.put("editor", it); vm = it }
        editorSlot.value = editor
        compose.setContent {
            density = androidx.compose.ui.platform.LocalDensity.current.density
            drawingView = androidx.compose.ui.platform.LocalView.current
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registry) {
            NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { NoteEditorScreen({}, {}, viewModel = editorSlot.value!!, imeInsets = androidx.compose.foundation.layout.WindowInsets(bottom = inset.intValue)) } }
        } }
        compose.waitUntil(10_000) { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); compose.waitForIdle(); editor.isReady }
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Голосовой ввод").performClick()
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

    @Test fun `tap dictation stays visible outside popup inserts once at selection and supports undo`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val editor = content()
        compose.onNodeWithText("Удерживайте микрофон и говорите").assertIsDisplayed()
        compose.onNodeWithContentDescription("Параметры диктовки").performClick()
        compose.onNodeWithText(VoiceControl.TAP.label).performClick()
        compose.onNodeWithContentDescription("Параметры диктовки").performClick()
        compose.onNodeWithText("Русский").performClick()
        compose.runOnIdle {
            editor.onContentChange(androidx.compose.ui.text.input.TextFieldValue("Original", androidx.compose.ui.text.TextRange(0, 8)))
            editor.onEditorFocus(com.notessync.app.notes.history.EditorField.TITLE)
        }
        compose.onNodeWithTag("voice-input").performClick()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        compose.runOnIdle {
            assertEquals("ru-RU", recognizer.lastRecognizerIntent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE))
            recognizer.triggerOnReadyForSpeech(Bundle())
            recognizer.triggerOnPartialResults(result("Проверка"))
        }
        compose.onNodeWithText("Проверка").assertIsDisplayed()
        compose.onNodeWithTag("voice-input").performClick()
        compose.runOnIdle {
            recognizer.triggerOnResults(result("Готовый текст")); recognizer.triggerOnResults(result("Дубль"))
            assertEquals("Готовый текст", editor.contentField.text)
            assertEquals(com.notessync.app.notes.history.EditorField.CONTENT, editor.editorField)
            assertTrue(recognizer.isDestroyed)
        }
        compose.onNodeWithTag("voice-panel").assertDoesNotExist()
        compose.runOnIdle { editor.undo(); assertEquals("Original", editor.contentField.text) }
    }
    @Test fun `preview cancels active recognition and rejects direct and late insertion`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val editor = content()
        compose.onNodeWithTag("voice-input").performClick()
        val recognizer = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        compose.runOnIdle { recognizer.triggerOnReadyForSpeech(Bundle()); editor.previewMode = true }
        compose.onNodeWithTag("voice-input").assertIsNotEnabled()
        compose.runOnIdle {
            assertTrue(recognizer.isDestroyed)
            recognizer.triggerOnResults(result("Late preview"))
            editor.insertVoiceText("Direct preview", VoiceStructure.RAW)
            assertEquals("Original", editor.contentField.text)
            editor.previewMode = false
        }
        compose.onNodeWithTag("voice-input").assertIsEnabled()
        compose.runOnIdle { assertTrue(recognizer.isDestroyed) }
    }
    @Test fun `closing panel cancels without accepting partial or late final text`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val editor = content()
        compose.onNodeWithTag("voice-input").performClick()
        val raw = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val recognizer = shadowOf(raw)
        compose.runOnIdle { recognizer.triggerOnReadyForSpeech(Bundle()); recognizer.triggerOnPartialResults(result("Do not save")) }
        compose.onNodeWithContentDescription("Закрыть диктовку").performClick()
        compose.onNodeWithTag("voice-panel").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(recognizer.isDestroyed); recognizer.triggerOnResults(result("Late final"))
            assertEquals("Original", editor.contentField.text)
        }
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Голосовой ввод").performClick()
        compose.runOnIdle { assertSame(raw, ShadowSpeechRecognizer.getLatestSpeechRecognizer()) }
    }
    @Test fun `changing editor owner destroys old session and requires a new explicit start`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val first = content()
        compose.onNodeWithTag("voice-input").performClick()
        val old = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        val recognizer = shadowOf(old)
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "Second", content = "Second original")) }
        val second = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { store.put("second", it) }
        compose.runOnIdle { recognizer.triggerOnReadyForSpeech(Bundle()); editorSlot.value = second; vm = second }
        compose.waitUntil(10_000) { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); compose.waitForIdle(); second.isReady }
        compose.onNodeWithTag("voice-panel").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(recognizer.isDestroyed); recognizer.triggerOnResults(result("Old owner text"))
            assertEquals("Original", first.contentField.text); assertEquals("Second original", second.contentField.text)
            assertSame(old, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        }
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithText("Голосовой ввод").performClick()
        compose.onNodeWithTag("voice-input").performClick()
        compose.runOnIdle {
            val fresh = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
            assertNotSame(old, fresh)
            shadowOf(fresh).triggerOnResults(result("New owner text"))
            assertEquals("Original", first.contentField.text)
            assertEquals("New owner text Second original", second.contentField.text)
        }
    }
    @Test fun `voice controls and document caret remain visible with long text and simulated keyboard`() {
        val editor = content((1..30).joinToString("\n") { "Строка $it" })
        compose.onNodeWithTag("voice-input").assertIsDisplayed()
        compose.onNodeWithContentDescription("Таблица").assertDoesNotExist()
        compose.runOnIdle { inset.intValue = (260 * density).toInt() }
        compose.onNodeWithTag("editor-body").performTextInputSelection(androidx.compose.ui.text.TextRange(editor.contentField.text.length))
        compose.onNodeWithTag("editor-body").performTextInput("!")
        val panel = compose.onNodeWithTag("voice-panel").fetchSemanticsNode().boundsInRoot
        val node = compose.onNodeWithTag("editor-body").fetchSemanticsNode()
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
        val cursor = layout.single().getCursorRect(editor.contentField.selection.end)
        val cursorTop = node.positionInRoot.y + cursor.top
        val cursorBottom = node.positionInRoot.y + cursor.bottom
        assertTrue("caret=$cursorTop panel=$panel", cursorTop >= panel.bottom)
        assertTrue("caret=$cursorBottom", cursorBottom < (760 - 260) * density)
        compose.onNodeWithContentDescription("Закрыть диктовку").assertIsDisplayed()
        compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(drawingView.width, drawingView.height, android.graphics.Bitmap.Config.ARGB_8888)
            drawingView.draw(android.graphics.Canvas(bitmap))
            val output = java.io.File("build/reports/voice-preview/panel-ime.png").apply { parentFile.mkdirs() }
            output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
