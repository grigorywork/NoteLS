package com.notessync.app.ai

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.screens.NoteEditorScreen
import com.notessync.app.ui.screens.SettingsScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickTransformEditorUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val settings get() = SettingsRepository.get(app)
    private val store = ViewModelStore()
    private lateinit var view: View
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private class Registry : ActivityResultRegistry(), ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) = Unit
    }
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        settings.apply { setUiLanguage(UiLanguage.RUSSIAN); setAiEnabled(true); setAiProvider(AiProvider.LOCAL_RULES)
            setQuickTransformButton(true); setBackupFrequency(BackupFrequency.MANUAL); setFontScale(SettingsRepository.FONT_NORMAL) }
        shadowOf(app).grantPermissions(Manifest.permission.INTERNET)
        shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setIsLowRamDevice(false)
    }
    @After fun after() { store.clear(); compose.waitForIdle(); AppDatabase.closeInstance()
        settings.apply { setAiEnabled(false); setQuickTransformButton(true); setUiLanguage(UiLanguage.RUSSIAN); setFontScale(SettingsRepository.FONT_NORMAL) } }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(15_000) { ShadowSystemClock.advanceBy(Duration.ofMillis(50)); compose.waitForIdle(); condition() }
    }
    private fun editor(lowRam: Boolean = false): NoteEditorViewModel {
        shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setIsLowRamDevice(lowRam)
        val id = runBlocking { AppDatabase.get(app).noteDao().upsert(NoteEntity(title = "Test", content = "Before one. two. After")) }
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id)))
        store.put("editor", vm)
        val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
        val registry = Registry()
        compose.setContent { CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registry) {
            NoteSyncTheme { val current = LocalView.current; SideEffect { view = current }
                Box(Modifier.requiredSize(320.dp, 760.dp)) { NoteEditorScreen({}, {}, vm) }
            }
        } }
        await { vm.isReady }
        return vm
    }
    @Test fun `quick action opens selection preview without replacing and keeps undo redo and menu reachable`() {
        val vm = editor()
        compose.runOnIdle { vm.onContentChange(TextFieldValue(vm.contentField.text, TextRange(7, 16))) }
        compose.onNodeWithTag("quick-transform").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Отменить").assertExists()
        compose.onNodeWithContentDescription("Повторить").assertExists()
        compose.onNodeWithContentDescription("Действия").assertIsDisplayed()
        screenshot("quick-transform-editor-320")
        compose.onNodeWithTag("quick-transform").performClick()
        await { compose.onAllNodesWithText("Текст для преобразования").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("one. two.")).assertExists()
        compose.onNodeWithText("Заменить текст").assertDoesNotExist()
        compose.onNodeWithText("Закрыть").performClick()
        compose.runOnIdle { assertEquals("Before one. two. After", vm.contentField.text) }
    }
    @Test fun `hiding shortcut keeps insert action and disabled preview or empty text cannot open it`() {
        val vm = editor()
        compose.runOnIdle { vm.previewMode = true }
        compose.onNodeWithTag("quick-transform").assertIsNotEnabled()
        compose.runOnIdle { vm.previewMode = false; vm.onContentChange(TextFieldValue("")) }
        compose.onNodeWithTag("quick-transform").assertIsNotEnabled()
        compose.runOnIdle { vm.onContentChange(TextFieldValue("Keep original")); settings.setQuickTransformButton(false) }
        compose.onNodeWithTag("quick-transform").assertDoesNotExist()
        compose.onNodeWithContentDescription("Вставить").performClick()
        compose.onNodeWithText("Преобразовать текст").assertIsEnabled().performClick()
        await { compose.onAllNodesWithText("Текст для преобразования").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun `low RAM editor hides shortcut while manual transform remains available`() {
        editor(lowRam = true)
        compose.onNodeWithTag("quick-transform").assertDoesNotExist()
        compose.onNodeWithContentDescription("Вставить").performClick()
        compose.onNodeWithText("Преобразовать текст").assertIsEnabled()
    }
    @Test fun `English setting hides shortcut without disabling provider on narrow large font screen`() {
        settings.apply { setUiLanguage(UiLanguage.ENGLISH); setFontScale(SettingsRepository.FONT_LARGE) }
        compose.setContent { NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { SettingsScreen({}, {}, "transform") } } }
        compose.onNodeWithText("Quick button in the editor").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(settings.state.value.quickTransformButton); assertTrue(settings.state.value.aiEnabled)
            assertEquals(AiProvider.LOCAL_RULES, settings.state.value.aiProvider) }
    }
    private fun screenshot(name: String) { compose.runOnIdle {
        val bitmap = Bitmap.createBitmap(320, 760, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/editor-preview/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    } }
}
