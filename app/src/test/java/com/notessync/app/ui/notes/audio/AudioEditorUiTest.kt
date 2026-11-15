package com.notessync.app.ui.notes.audio

import android.Manifest
import android.app.Application
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.audio.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
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
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AudioEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private var editor: NoteEditorViewModel? = null
    private val captures = CopyOnWriteArrayList<Capture>()
    private val players = CopyOnWriteArrayList<Player>()
    private val back = AtomicBoolean(false)
    private class Owner : LifecycleOwner { val registry = LifecycleRegistry(this); override val lifecycle: Lifecycle = registry }
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        var code: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) { code = requestCode }
    }
    private class Capture : AudioCaptureBackend {
        var path = ""; var releases = 0
        override fun prepare(path: String) { this.path = path; File(path).writeBytes(ByteArray(4097) { (it % 251).toByte() }) }
        override fun start() = Unit
        override fun stop() = Unit
        override fun release() { releases++ }
    }
    private class Player(val callbacks: AudioPlaybackCallbacks) : AudioPlaybackBackend {
        var releases = 0
        override fun prepare(path: String) = Unit
        override fun play(positionMillis: Long) = Unit
        override val positionMillis = 2_000L
        override val durationMillis = 10_000L
        override fun release() { releases++ }
    }
    private val owner = Owner(); private val registry = Registry()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setAudioRecording(true); setBackupFrequency(BackupFrequency.MANUAL) }
        owner.registry.currentState = Lifecycle.State.RESUMED
    }
    @After fun after() {
        editor?.let { vm ->
            val drained = AtomicBoolean(false)
            compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch {
                vm.audioCapture.cancel().await(); vm.audioPlayback.stop().await(); vm.saveNow(force = true); drained.set(true)
            } }
            await { drained.get() }
        }
        store.clear(); compose.waitForIdle(); AppDatabase.closeInstance()
    }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); compose.waitForIdle(); condition() }
    }
    private fun content(): NoteEditorViewModel {
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "Audio", content = "Original")) }
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id)), EditorAudioDependencies(
            AudioFiles(app) { AudioMetadata(1000, "audio/mp4") }, AudioCaptureFactory { Capture().also(captures::add) },
            AudioPlaybackFactory { Player(it).also(players::add) }))
        store.put("audio", vm); editor = vm
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registry) {
            NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { NoteEditorScreen({ back.set(true) }, {}, vm) } }
        } }
        await { vm.isReady }; return vm
    }
    private fun chooseRecording() {
        compose.onNodeWithContentDescription("Вставить").performClick()
        compose.onNodeWithText("Записать аудио").performClick()
    }
    @OptIn(ExperimentalTestApi::class)
    private fun assertVoiceDisabled() {
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.onNodeWithTag("voice-input").assertIsNotEnabled()
        compose.onNode(isPopup()).performKeyInput { pressKey(Key.Back) }
    }
    private fun record(vm: NoteEditorViewModel) {
        chooseRecording()
        await { vm.audioCapture.state.value.phase == AudioCapturePhase.RECORDING }
    }
    @Test fun `audio has its own controls never enters photo previews and playback stops on pause`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val vm = content(); record(vm)
        assertVoiceDisabled()
        compose.onNodeWithContentDescription("Сохранить аудио").performClick()
        await { !vm.audioCapture.state.value.busy && vm.attachments.value.size == 1 }
        val row = vm.attachments.value.single()
        compose.onNodeWithTag("audio-${row.syncId}").assertExists()
        compose.onAllNodesWithContentDescription("Фото заметки").assertCountEquals(0)
        compose.onNodeWithContentDescription("Воспроизвести аудио").performScrollTo().performClick(); await { players.isNotEmpty() }
        compose.runOnIdle { players.single().callbacks.prepared() }; await { vm.audioPlayback.state.value.phase == AudioPlaybackPhase.PLAYING }
        assertVoiceDisabled()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        await { vm.audioPlayback.state.value.phase == AudioPlaybackPhase.IDLE }
        compose.runOnIdle { assertEquals(1, players.single().releases); players.single().callbacks.prepared(); owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(AudioPlaybackPhase.IDLE, vm.audioPlayback.state.value.phase); assertEquals(1, players.size); assertEquals("Original", vm.contentField.text) }
    }
    @Test fun `permission answer never records until another explicit selection and cancellation leaves no attachment`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val vm = content()
        chooseRecording()
        compose.runOnIdle {
            assertNotNull(registry.code); assertTrue(captures.isEmpty())
            owner.registry.currentState = Lifecycle.State.CREATED
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO); registry.dispatchResult(registry.code!!, true)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.runOnIdle { assertTrue(captures.isEmpty()) }
        record(vm); compose.onNodeWithContentDescription("Отменить запись").performClick()
        await { !vm.audioCapture.state.value.busy }
        compose.runOnIdle { assertEquals(1, captures.single().releases); assertFalse(File(captures.single().path).exists()); assertTrue(vm.attachments.value.isEmpty()) }
    }
    @Test fun `pause saves active recording and Back waits for a second recording without late restart`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val vm = content(); record(vm)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        await { !vm.audioCapture.state.value.busy && vm.attachments.value.size == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.runOnIdle { assertEquals(1, captures.size) }
        record(vm); compose.onNodeWithContentDescription("Назад").performClick()
        await { back.get() && !vm.audioCapture.state.value.busy && vm.attachments.value.size == 2 }
        compose.onNodeWithContentDescription("Действия").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, captures.size); assertTrue(captures.all { it.releases == 1 && !File(it.path).exists() }); assertEquals("Original", vm.contentField.text) }
    }
}
