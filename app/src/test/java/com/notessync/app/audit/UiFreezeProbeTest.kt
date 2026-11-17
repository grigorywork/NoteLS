package com.notessync.app.audit

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.notes.text.*
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.NotesViewModel
import com.notessync.app.ui.screens.*
import com.notessync.app.ui.theme.NoteSyncTheme
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/** Isolated diagnostic only: elapsed real operations, never virtual-clock duration or idle sleep. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiFreezeProbeTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear(); AppDatabase.closeInstance() }

    @Test(timeout = 8_100_000) fun `real editor settings and list repeatedly respond under sustained native load`() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        val settings = SettingsRepository.get(app).apply {
            setUiLanguage(UiLanguage.RUSSIAN); setBackupFrequency(BackupFrequency.MANUAL)
            setAutoSyncEnabled(false); setEditorHistory(true); setAppFont(AppFont.SYSTEM)
        }
        val original = (0 until 120).joinToString("\n") { "- [ ] Строка $it **жирный** текст 😀" }
        val format = TextFormatting(marks = (0 until 1000).map {
            FormatMark(it * 2, it * 2 + 1, FormatKind.COLOR.name, if (it % 2 == 0) "FF1565C0" else "FFB71C1C")
        })
        val db = AppDatabase.get(app)
        val id = runBlocking {
            val id = db.noteDao().upsert(NoteEntity(title = "Audit editor", content = original, textFormat = format.encode()))
            repeat(100) { index -> db.noteDao().upsert(NoteEntity(title = "Audit $index", content = original.take(500),
                isTask = index % 3 == 0, isCompleted = index % 7 == 0)) }
            id
        }
        val editor = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to id))).also { store.put("editor", it) }
        val list = NotesViewModel(app).also { store.put("list", it) }
        var screen by mutableIntStateOf(0)
        compose.setContent {
            val state by settings.state.collectAsState()
            NoteSyncTheme(darkTheme = state.themeMode == ThemeMode.DARK, dynamicColor = false,
                interfaceStyle = state.interfaceStyle, glassAppearance = state.glassAppearance,
                fontScale = state.fontScale, appFont = state.appFont) {
                Box(Modifier.requiredSize(360.dp, 720.dp)) {
                    when (screen) {
                        0 -> NoteEditorScreen({}, {}, editor)
                        1 -> SettingsScreen({}, {})
                        else -> NotesListScreen({}, {}, {}, {}, {}, { _, _ -> }, viewModel = list)
                    }
                }
            }
        }
        compose.waitUntil(15_000) { compose.waitForIdle(); editor.isReady }
        val targetMs = System.getenv("NOTELS_AUDIT_ACTIVE_MS")?.toLong() ?: 60_000L
        require(targetMs in 60_000L..7_200_000L)
        val started = Instant.now().toString()
        var activeNanos = 0L
        var cycles = 0L
        val counts = LongArray(4)
        val maximum = LongArray(4)
        val total = LongArray(4)
        val detailCounts = LongArray(7)
        val detailTotal = LongArray(7)
        val detailMaximum = LongArray(7)
        val report = File("build/reports/freeze-probe/progress.json").apply { parentFile?.mkdirs() }
        fun checkpoint(status: String) {
            val heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            report.writeText("""{"status":"$status","started":"$started","observed":"${Instant.now()}","target_ms":$targetMs,"active_ms":${activeNanos / 1_000_000},"cycles":$cycles,"counts":${counts.contentToString()},"operation_total_ns":${total.contentToString()},"operation_max_ns":${maximum.contentToString()},"heap_bytes":$heap,"groups":["editor_history","task_mode","settings","list_scroll"],"settings_detail_counts":${detailCounts.contentToString()},"settings_detail_total_ns":${detailTotal.contentToString()},"settings_detail_max_ns":${detailMaximum.contentToString()},"settings_detail_groups":["theme_scale","editor_section","appearance_section","font_change","glass_save","language_roundtrip","close_section"],"sdk":28,"native_canvas":true,"physical_phone":false}""")
        }
        fun operation(group: Int, block: () -> Unit) {
            val begin = System.nanoTime()
            try { block() } finally {
                val elapsed = System.nanoTime() - begin
                activeNanos += elapsed; counts[group]++; total[group] += elapsed
                maximum[group] = maxOf(maximum[group], elapsed)
            }
        }
        // Nested detail timings do not add to activeNanos: no double-counting.
        fun detail(group: Int, block: () -> Unit) {
            val begin = System.nanoTime()
            try { block() } finally {
                val elapsed = System.nanoTime() - begin
                detailCounts[group]++; detailTotal[group] += elapsed
                detailMaximum[group] = maxOf(detailMaximum[group], elapsed)
            }
        }
        try {
            checkpoint("running")
            while (activeNanos / 1_000_000 < targetMs) {
                operation(0) {
                    compose.runOnIdle { screen = 0 }
                    compose.onNodeWithTag("editor-task-toggle").performScrollTo().assertIsDisplayed()
                    val changed = original + "\nИзменение $cycles"
                    compose.runOnIdle { editor.onContentChange(TextFieldValue(changed, TextRange(changed.length))) }
                    compose.waitForIdle()
                    compose.runOnIdle { assertEquals(changed, editor.contentField.text); editor.undo() }
                    compose.runOnIdle { assertEquals(original, editor.contentField.text); editor.redo() }
                    compose.runOnIdle { assertEquals(changed, editor.contentField.text); editor.undo() }
                    compose.runOnIdle { assertEquals(original, editor.contentField.text) }
                }
                operation(1) {
                    compose.onNodeWithTag("editor-task-toggle").assertIsOff().assertIsEnabled().performScrollTo().assertIsDisplayed().performClick()
                    compose.waitUntil(15_000) { compose.waitForIdle(); editor.isTask && !editor.taskProtectionBusy }
                    compose.onNodeWithTag("editor-task-toggle").assertIsOn()
                    compose.onNodeWithTag("editor-task-color").assertExists()
                    compose.onNodeWithTag("editor-task-toggle").performScrollTo().assertIsDisplayed().performClick()
                    compose.waitUntil(15_000) { compose.waitForIdle(); !editor.isTask && !editor.taskProtectionBusy }
                    compose.onNodeWithTag("editor-task-toggle").assertIsOff().assertIsDisplayed()
                }
                operation(2) {
                    detail(0) {
                    compose.runOnIdle {
                        screen = 1
                        settings.setThemeMode(if (cycles % 2L == 0L) ThemeMode.DARK else ThemeMode.LIGHT)
                        settings.setInterfaceStyle(if (cycles % 3L == 0L) InterfaceStyle.GLASS else InterfaceStyle.CLASSIC)
                        settings.setFontScale(if (cycles % 4L == 0L) SettingsRepository.FONT_LARGE else 1f)
                    }
                    }
                    detail(1) {
                    compose.onNodeWithTag("settings-group-editor").performScrollTo().performClick()
                    compose.onNodeWithText("История изменений").performScrollTo().assertIsDisplayed()
                    }
                    detail(2) {
                    compose.onNodeWithContentDescription("Назад").performClick()
                    compose.onNodeWithTag("settings-group-appearance").performScrollTo().performClick()
                    }
                    detail(3) {
                    val font = AppFont.entries[(cycles % AppFont.entries.size).toInt()]
                    compose.onNodeWithText(font.label).performScrollTo().performClick()
                    compose.runOnIdle { assertEquals(font, settings.state.value.appFont) }
                    }
                    if (cycles % 3L == 0L) {
                        detail(4) {
                        val transparency = if (cycles % 2L == 0L) 0.7f else 0.3f
                        val frost = if (cycles % 2L == 0L) 0.5f else 0f
                        compose.onNodeWithTag("settings-glass-open").performScrollTo().performClick()
                        compose.onNodeWithTag("glass-transparency").performScrollTo()
                            .performSemanticsAction(SemanticsActions.SetProgress) { it(transparency) }
                        compose.onNodeWithTag("glass-frost").performScrollTo()
                            .performSemanticsAction(SemanticsActions.SetProgress) { it(frost) }
                        compose.onNodeWithTag("glass-save").performClick()
                        compose.runOnIdle {
                            assertEquals(transparency, settings.state.value.glassAppearance.transparency, 0.001f)
                            assertEquals(frost, settings.state.value.glassAppearance.frost, 0.001f)
                        }
                        }
                    }
                    detail(5) {
                        compose.onNodeWithText("English").performScrollTo().performClick()
                        compose.onNodeWithText("Settings").assertIsDisplayed()
                        compose.onNodeWithText("Русский").performScrollTo().performClick()
                        compose.onNodeWithText("Настройки").assertIsDisplayed()
                    }
                    detail(6) {
                    compose.onNodeWithContentDescription("Назад").performClick()
                    }
                }
                operation(3) {
                    compose.runOnIdle { screen = 2; settings.setNotesLayout(if (cycles % 2L == 0L) NotesLayout.GRID else NotesLayout.LIST) }
                    compose.waitUntil(15_000) {
                        // Room's debounced query uses Android uptime, not real time or Compose's clock.
                        // This virtual advancement is deliberately excluded from the duration counter.
                        ShadowSystemClock.advanceBy(Duration.ofMillis(350))
                        compose.waitForIdle(); compose.onAllNodesWithTag("notes-cards").fetchSemanticsNodes().isNotEmpty()
                    }
                    compose.onNodeWithTag("notes-cards").performTouchInput { swipeUp() }
                    compose.onNodeWithTag("notes-cards").performTouchInput { swipeDown() }
                    compose.onNodeWithTag("notes-cards").assertIsDisplayed()
                }
                cycles++
                if (cycles % 10L == 0L) { checkpoint("running"); println("FREEZE_AUDIT cycles=$cycles active_ms=${activeNanos / 1_000_000}") }
            }
            assertTrue(counts.all { it > 0 }); assertTrue(activeNanos / 1_000_000 >= targetMs)
            checkpoint("success")
        } catch (failure: Throwable) { checkpoint("failure"); throw failure }
    }
}
