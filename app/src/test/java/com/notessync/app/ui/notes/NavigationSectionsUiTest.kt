package com.notessync.app.ui.notes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.notes.diary.DiaryEntries
import com.notessync.app.security.ProtectionSession
import com.notessync.app.settings.*
import com.notessync.app.ui.calendar.CalendarViewModel
import com.notessync.app.ui.screens.CalendarScreen
import com.notessync.app.ui.screens.NotesListScreen
import com.notessync.app.ui.theme.NoteSyncTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationSectionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val models = ViewModelStore()
    private lateinit var drawingView: View
    private val opened = AtomicLong()
    private val agenda = AtomicInteger()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL)
            setNotesLayout(NotesLayout.LIST); setArchiveCompletedTasks(false); setFontScale(SettingsRepository.FONT_NORMAL) }
    }
    @After fun after() {
        models.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance()
        SettingsRepository.get(app).apply { setArchiveCompletedTasks(false); setUiLanguage(UiLanguage.RUSSIAN) }
    }
    private fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(350)); compose.waitForIdle(); condition()
    }
    private fun exists(id: Long) = compose.onAllNodesWithTag("note-card-$id").fetchSemanticsNodes().isNotEmpty()
    private fun home(): NotesViewModel {
        val vm = NotesViewModel(app).also { models.put("home", it) }
        compose.setContent {
            drawingView = LocalView.current
            NoteSyncTheme(darkTheme = true, dynamicColor = false) { Box(Modifier.requiredSize(320.dp, 760.dp).testTag("nav-viewport")) {
                NotesListScreen({}, {}, {}, {}, {}, { id, _ -> opened.set(id) }, viewModel = vm,
                    onOpenAgenda = { agenda.incrementAndGet() })
            } }
        }
        await { vm.results.value != null }
        return vm
    }
    private fun section(label: String) {
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithContentDescription("Разделы заметок").performClick()
        compose.onNodeWithText(label).performClick()
    }
    private fun snapshot(name: String) {
        val bounds = compose.onNodeWithTag("nav-viewport").fetchSemanticsNode().boundsInRoot
        val file = File("build/reports/navigation-preview/$name.png").apply { parentFile.mkdirs() }
        compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt(), bounds.height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap); canvas.translate(-bounds.left, -bounds.top); drawingView.draw(canvas)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    @Test fun `completed preference updates cached lists and unchecking returns the same record`() {
        val db = AppDatabase.get(app)
        val done = runBlocking { db.noteDao().upsert(NoteEntity(title = "Done", isTask = true, isCompleted = true)) }
        val active = runBlocking { db.noteDao().upsert(NoteEntity(title = "Active", isTask = true)) }
        home(); await { exists(done) && exists(active) }
        compose.runOnIdle { SettingsRepository.get(app).setArchiveCompletedTasks(true) }
        compose.onNodeWithTag("note-card-$done").assertDoesNotExist()
        await { exists(active) }
        section("Выполненные задачи"); await { exists(done) && !exists(active) }
        compose.onNodeWithContentDescription("Аналитика").assertIsDisplayed()
        compose.onNodeWithContentDescription("Новая заметка").assertDoesNotExist()
        snapshot("completed")
        compose.onNodeWithContentDescription("Снять выполнение: Done").performClick()
        await { !exists(done) }
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Все заметки").performClick()
        await { exists(done) && exists(active) }
        runBlocking { assertFalse(db.noteDao().getById(done)!!.isCompleted) }
    }
    @Test fun `drawer keeps secondary sections in overflow and journal creates a dated regular note`() {
        val vm = home()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithContentDescription("Разделы заметок").assertIsDisplayed()
        listOf("Корзина", "Невыполненные", "Просроченные", "Аналитика").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        snapshot("drawer")
        compose.onNodeWithContentDescription("Разделы заметок").performClick()
        compose.onNodeWithText("Дневник").performClick()
        await { vm.results.value?.key?.filter == NoteFilter.Tag(DiaryEntries.TAG_NAME) }
        compose.onNodeWithContentDescription("Новая запись дневника").performClick()
        await { opened.get() > 0 }
        runBlocking {
            val note = AppDatabase.get(app).noteDao().getById(opened.get())!!
            assertFalse(note.isTask); assertNotNull(note.dayKey); assertTrue(note.title.isNotBlank())
            assertEquals(listOf(DiaryEntries.TAG_NAME), AppDatabase.get(app).tagDao().getTagNamesForNote(note.id))
        }
        section("Ежедневник"); assertEquals(1, agenda.get())
    }
    @Test fun `English daily agenda keeps the month collapsed and offers date and month navigation`() {
        SettingsRepository.get(app).setUiLanguage(UiLanguage.ENGLISH)
        val vm = CalendarViewModel(app).also { models.put("calendar", it) }
        compose.setContent {
            drawingView = LocalView.current
            NoteSyncTheme(darkTheme = true, dynamicColor = false) { Box(Modifier.requiredSize(320.dp, 760.dp).testTag("nav-viewport")) {
                CalendarScreen({}, { _, _ -> }, { _, _ -> }, viewModel = vm, agendaMode = true)
            } }
        }
        compose.onNodeWithText("Daily planner").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous month").assertDoesNotExist()
        val before = vm.state.value.selectedDate
        compose.onNodeWithContentDescription("Next day").performClick()
        await { vm.state.value.selectedDate == before.plusDays(1) }
        snapshot("agenda")
        compose.onNodeWithContentDescription("Show month").performClick()
        compose.onNodeWithContentDescription("Previous month").assertIsDisplayed()
        compose.onNodeWithContentDescription("Show daily agenda").performClick()
        compose.onNodeWithContentDescription("Previous month").assertDoesNotExist()
    }
}
