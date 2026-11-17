package com.notessync.app.i18n

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.*
import com.notessync.app.ui.screens.*
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanguageUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val app get() = RuntimeEnvironment.getApplication()
    private var editor: NoteEditorViewModel? = null
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply {
            setBackupFrequency(BackupFrequency.MANUAL); setUiLanguage(UiLanguage.RUSSIAN); setEditorHistory(true)
        }
    }
    @After fun after() {
        store.clear()
        editor?.let { vm ->
            val drained = AtomicBoolean(false)
            compose.runOnIdle { CoroutineScope(Dispatchers.Main.immediate).launch { vm.saveNow(force = true); drained.set(true) } }
            compose.waitUntil(5_000) { compose.waitForIdle(); drained.get() }
        }
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN)
        AppDatabase.closeInstance()
    }
    @Test fun `settings switch updates labels and scoped resources without replacing the activity`() {
        val resourcesLocale = java.util.concurrent.atomic.AtomicReference<String>()
        val activity = java.util.concurrent.atomic.AtomicReference<android.content.Context>()
        compose.setContent {
            NoteSyncTheme {
                val context = androidx.compose.ui.platform.LocalContext.current
                SideEffect {
                    resourcesLocale.set(context.resources.configuration.locales[0].language)
                    activity.set((context as android.content.ContextWrapper).baseContext)
                }
                Box(Modifier.requiredSize(320.dp, 760.dp)) { SettingsScreen({}, {}) }
            }
        }
        val initial = activity.get()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Interface language").assertIsDisplayed()
        compose.runOnIdle { assertEquals("en", resourcesLocale.get()); assertSame(initial, activity.get()) }
        compose.onNodeWithText("Русский").performClick()
        compose.onNodeWithText("Настройки").assertIsDisplayed()
        compose.runOnIdle { assertEquals("ru", resourcesLocale.get()); assertSame(initial, activity.get()) }
    }

    @Test fun `switching language preserves the editor draft and undo history`() {
        val settings = SettingsRepository.get(app)
        settings.setUiLanguage(UiLanguage.ENGLISH)
        var created = 0
        compose.setContent {
            val vm = remember { NoteEditorViewModel(app, SavedStateHandle()).also { store.put("editor", it); editor = it; created++ } }
            NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { NoteEditorScreen({}, {}, viewModel = vm) } }
        }
        val body = compose.onAllNodes(hasSetTextAction()).onLast()
        val original = "Удалить %1" + '$' + "s 😀"
        body.performTextInput(original)
        compose.onNodeWithContentDescription("Undo").assertIsEnabled()
        compose.runOnIdle { settings.setUiLanguage(UiLanguage.RUSSIAN) }
        body.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(original)))
        compose.onNodeWithContentDescription("Отменить").performClick()
        body.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithContentDescription("Повторить").performClick()
        body.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(original)))
        assertEquals(1, created)
    }
    @Test fun `English home actions do not translate matching Russian note titles or write notes`() {
        val db = AppDatabase.get(app)
        val note = NoteEntity(title = "Удалить", content = "Мои записи %1" + '$' + "s 😀", updatedAt = 123L, dirty = false)
        val id = runBlocking { db.noteDao().upsert(note) }
        val settings = SettingsRepository.get(app); settings.setUiLanguage(UiLanguage.ENGLISH)
        compose.setContent {
            val vm = remember { NotesViewModel(app).also { store.put("home", it) } }
            NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) { NotesListScreen({}, {}, {}, {}, {}, { _, _ -> }, viewModel = vm) } }
        }
        compose.waitUntil(10_000) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(350)); compose.waitForIdle()
            compose.onAllNodesWithTag("note-card-$id").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Удалить").assertIsDisplayed()
        compose.onNodeWithContentDescription("Search notes").assertIsDisplayed()
        compose.onNode(hasContentDescription("Actions") and hasAnyAncestor(hasTestTag("note-card-$id"))).performClick()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Pin").assertIsDisplayed()
        assertEquals(note.copy(id = id), runBlocking { db.noteDao().getById(id) })
    }
}
