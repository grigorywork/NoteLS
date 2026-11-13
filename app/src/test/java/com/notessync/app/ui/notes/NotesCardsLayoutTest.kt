package com.notessync.app.ui.notes

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.NotesLayout
import com.notessync.app.ui.home.NotesCardsLayout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotesCardsLayoutTest {
    @get:Rule val compose = createComposeRule()
    private fun notes(count: Int) = (1..count).map { i -> NoteEntity(id = i.toLong(), title = "Note $i",
        content = if (i % 2 == 1) "Long line\n".repeat(12) else "Short") }

    @Test fun `two lanes have varying height and photos while distant notes remain uncomposed`() {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.cacheDir, "grid-photo.jpg")
        val source = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 80, it) }; source.recycle()
        val bytes = file.readBytes()
        val photos = mapOf(1L to AttachmentEntity(id = 1, noteId = 1, filePath = file.path, mimeType = "image/jpeg", sizeBytes = file.length(), displayWidthDp = 144, displayHeightDp = 720))
        val data = notes(100)
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(320.dp, 720.dp)) {
                NotesCardsLayout(data, emptyList(), NotesLayout.GRID, photos, true,
                    onOpen = {}, onUnlock = {}, onDelete = {}, onToggleCompleted = {}, onToggleTimer = {})
            }
        } }
        compose.waitUntil(5_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("Фото заметки", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val first = compose.onNodeWithTag("note-card-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("note-card-2").fetchSemanticsNode().boundsInRoot
        assertTrue(first.left < second.left); assertTrue(first.height > second.height)
        val photo = compose.onNodeWithContentDescription("Фото заметки", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(photo.height <= 180 * app.resources.displayMetrics.density)
        compose.onNodeWithTag("note-card-100").assertDoesNotExist()
        assertArrayEquals(bytes, file.readBytes()); file.delete()
    }

    @Test fun `same anchor survives lane changes and saved instance restoration`() {
        val data = notes(80); var layout by mutableStateOf(NotesLayout.LIST)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme {
            Box(Modifier.requiredSize(320.dp, 720.dp)) {
                NotesCardsLayout(data, emptyList(), layout, emptyMap(), true,
                    onOpen = {}, onUnlock = {}, onDelete = {}, onToggleCompleted = {}, onToggleTimer = {})
            }
        } }
        compose.onNodeWithTag("notes-cards").performScrollToIndex(20)
        compose.onNodeWithTag("note-card-21").assertIsDisplayed()
        compose.runOnIdle { layout = NotesLayout.GRID }
        compose.onNodeWithTag("note-card-21").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("note-card-21").assertIsDisplayed()
        compose.runOnIdle { layout = NotesLayout.LIST }
        compose.onNodeWithTag("note-card-21").assertIsDisplayed()
    }

    @Test fun `compact actions remain distinct and locked cards expose no note content or photo`() {
        var opened = 0; var unlocked = 0; var deleted = 0; var completed = 0; var timer = 0
        val task = NoteEntity(id = 1, title = "Task", isTask = true, taskPriority = 3, isFavorite = true, tablesData = "[]")
        val locked = NoteEntity(id = 2, title = "SECRET", content = "SECRET", isTask = true, isProtected = true)
        val hiddenPhoto = AttachmentEntity(noteId = 2, filePath = "never-read-this-file", mimeType = "image/jpeg", sizeBytes = 3)
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(240.dp, 720.dp)) {
                NotesCardsLayout(listOf(task, NoteEntity(id = 3, title = "Narrow")), listOf(locked), NotesLayout.GRID, mapOf(2L to hiddenPhoto), true,
                    onOpen = { opened++ }, onUnlock = { unlocked++ }, onDelete = { deleted++ },
                    onToggleCompleted = { completed++ }, onToggleTimer = { timer++ })
            }
        } }
        val first = compose.onNodeWithTag("note-card-1").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("note-card-3").fetchSemanticsNode().boundsInRoot
        assertEquals(first.left, second.left, .1f)
        assertTrue(first.right <= 240 * RuntimeEnvironment.getApplication().resources.displayMetrics.density)
        compose.onNodeWithText("SECRET").assertDoesNotExist()
        compose.onNodeWithText("Защищённая задача").performClick()
        compose.onNodeWithContentDescription("Выполнить задачу: Task", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Запустить таймер").performClick()
        compose.onNodeWithTag("note-card-1").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNode(hasContentDescription("Действия") and hasAnyAncestor(hasTestTag("note-card-1"))).performClick()
        compose.onNodeWithText("Удалить").performClick()
        assertEquals(1, opened); assertEquals(1, unlocked); assertEquals(1, deleted); assertEquals(1, completed); assertEquals(1, timer)
        compose.onNodeWithContentDescription("Фото недоступно").assertDoesNotExist()
    }
}
