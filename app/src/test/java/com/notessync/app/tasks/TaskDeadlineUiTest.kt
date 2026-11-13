package com.notessync.app.tasks

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.entity.TagEntity
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.tasks.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h700dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskDeadlineUiTest {
    @get:Rule val compose = createComposeRule()
    @Before fun before() { SettingsRepository.get(RuntimeEnvironment.getApplication()).setUiLanguage(UiLanguage.RUSSIAN) }
    @Test fun `deadline picker selects a separate date and time then removes the deadline explicitly`() {
        var due by mutableStateOf<Long?>(null)
        compose.setContent { MaterialTheme { TaskDeadlinePicker(due, true) { due = it } } }
        compose.onNodeWithText("Срок задачи").performClick()
        compose.onNodeWithText("Далее").performClick()
        compose.onNodeWithText("Время срока").assertIsDisplayed()
        compose.onNodeWithText("Срок не включает напоминание или таймер.").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("18")
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("30")
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle {
            val selected = Instant.ofEpochMilli(due!!).atZone(ZoneId.systemDefault())
            assertEquals(LocalDate.now(), selected.toLocalDate()); assertEquals(LocalTime.of(18, 30), selected.toLocalTime())
        }
        compose.onNode(hasText("Срок:", substring = true)).performClick()
        compose.onNodeWithText("Убрать срок").performClick()
        compose.runOnIdle { assertNull(due) }
    }
    @Test fun `combined filters keep priority with overdue and lazily select a notebook among two thousand choices`() {
        var filters by mutableStateOf(TaskFilters(due = TaskDueFilter.OVERDUE))
        val notebooks = (1L..2_000L).map { NotebookEntity(id = it, name = "Book $it") }
        val tags = listOf(TagEntity(id = 7, name = "Удалить %1" + '$' + "s"))
        compose.setContent { MaterialTheme { TaskFiltersBar(filters, false, notebooks, tags) { filters = it } } }
        compose.onNodeWithText("Все приоритеты").performClick(); compose.onNodeWithText("Высокий").performClick()
        compose.runOnIdle { assertEquals(TaskDueFilter.OVERDUE, filters.due); assertEquals(3, filters.priority) }
        compose.onNodeWithText("Все блокноты").performScrollTo().performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(2_001)
        compose.onNodeWithText("Book 2000").performClick()
        compose.runOnIdle { assertEquals(2_000L, filters.notebookId) }
        compose.onNodeWithText("Все теги").performScrollTo().performClick()
        compose.onNodeWithText("#" + tags.single().name).performClick()
        compose.runOnIdle { assertEquals(7L, filters.tagId) }
        compose.onNodeWithText("Сбросить фильтры").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(TaskFilters(), filters) }
    }
}
