package com.notessync.app.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.R
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickTaskUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private lateinit var app: Application
    @Before fun before() {
        app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); AppDatabase.closeInstance(); SettingsRepository.get(app).setQuickTaskTime(true) }

    @Test fun `plus clears only after saving and the small editor has no save button`() {
        SettingsRepository.get(app).setQuickTaskTime(false)
        lateinit var vm: QuickTaskViewModel
        compose.setContent {
            vm = remember { QuickTaskViewModel(app, SavedStateHandle()).also { models.put("quick", it) } }
            MaterialTheme { Box(Modifier.requiredSize(320.dp, 440.dp)) { QuickNoteContent(vm, false, {}) } }
        }
        compose.onNodeWithText("Сохранить", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("First quick task")
        compose.onNodeWithContentDescription("Сохранить и новая задача").assertIsDisplayed().performClick()
        // Room resumes on Robolectric's paused main looper; drive Android idle as well as Compose frames.
        compose.waitUntil(5_000) { compose.waitForIdle(); vm.text.isEmpty() && !vm.busy }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        val plus = compose.onNodeWithContentDescription("Сохранить и новая задача").fetchSemanticsNode().boundsInRoot
        assertTrue(plus.left >= 0); assertTrue(plus.right <= 320 * app.resources.displayMetrics.density)
    }

    @Test fun `time-first setting opens a compact picker and cancellation creates no task`() {
        SettingsRepository.get(app).setQuickTaskTime(true)
        lateinit var vm: QuickTaskViewModel
        compose.setContent {
            vm = remember { QuickTaskViewModel(app, SavedStateHandle()).also { models.put("quick", it) } }
            MaterialTheme { QuickNoteContent(vm, true, {}) }
        }
        compose.onNodeWithText("Время напоминания").assertIsDisplayed()
        compose.onNodeWithText("Без напоминания").performClick()
        compose.runOnIdle { assertNull(vm.reminderAt); assertEquals(0L, vm.currentId); assertEquals("", vm.text) }
    }

    @Test fun `widget plus carries its selected day and a unique widget identity`() {
        val manager = shadowOf(AppWidgetManager.getInstance(app))
        val id = manager.createWidget(CalendarWidgetProvider::class.java, R.layout.widget_calendar)
        val day = LocalDate.now().plusDays(5).toEpochDay()
        CalendarWidgetProvider.selectDay(app, id, day)
        assertTrue(manager.getViewFor(id).findViewById<View>(R.id.widget_add).performClick())
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(day, intent.getLongExtra(CalendarWidgetProvider.EXTRA_DAY_KEY, -1))
        assertTrue(intent.getBooleanExtra(QuickNoteActivity.EXTRA_QUICK_ADD, false))
        assertEquals("notels://widget/$id/quick-add", intent.dataString)
        assertEquals(id, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
    }
}
