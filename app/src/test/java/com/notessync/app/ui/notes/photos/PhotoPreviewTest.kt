package com.notessync.app.ui.notes.photos

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.notes.photos.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoPreviewTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `side and corner handles resize draft and consecutive gestures start at latest size`() {
        val size = mutableStateOf(PhotoDisplaySize(180, 180))
        compose.setContent { MaterialTheme { Box(Modifier.requiredSize(320.dp, 500.dp)) {
            PhotoFrame(size.value, true, { size.value = it }, Modifier.fillMaxSize()) { }
        } } }
        compose.onNodeWithTag("photo-handle-RIGHT").performTouchInput { swipeRight() }
        val after = size.value
        assertTrue(after.width > 180); assertEquals(180, after.height)
        compose.onNodeWithTag("photo-handle-RIGHT").performTouchInput { swipeRight() }
        assertTrue(size.value.width > after.width)
        compose.onNodeWithTag("photo-handle-BOTTOM_RIGHT").performTouchInput { swipe(start = center, end = center + androidx.compose.ui.geometry.Offset(20f, 30f)) }
        assertTrue(size.value.height > 180)
    }
    @Test fun `preview requires explicit save while back cancels draft and setting hides handles`() {
        val resize = mutableStateOf(true)
        val shown = mutableStateOf(true)
        val photo = AttachmentEntity(id = 1, noteId = 1, filePath = "missing", mimeType = "image/jpeg", sizeBytes = 1)
        val saves = mutableListOf<PhotoDisplaySize>(); var closes = 0
        compose.setContent { MaterialTheme {
            if (shown.value) PhotoPreviewDialog(photo, resize.value, remember { SnackbarHostState() },
                onSave = { saves.add(it); true }, onOriginal = {}, onDismiss = { closes++; shown.value = false })
        } }
        compose.onNodeWithContentDescription("Увеличить ширину").performClick()
        assertTrue(saves.isEmpty())
        compose.onNodeWithContentDescription("Закрыть фото").performClick()
        assertEquals(1, closes); assertTrue(saves.isEmpty())
        compose.runOnIdle { shown.value = true }
        compose.onNodeWithText("Ширина 100%").assertExists()
        compose.onNodeWithContentDescription("Увеличить ширину").performClick()
        compose.onNodeWithText("Готово").performClick()
        compose.waitForIdle()
        assertEquals(1, saves.size); assertEquals(264, saves.single().width)
        compose.runOnIdle { resize.value = false; shown.value = true }
        compose.onNodeWithTag("photo-handle-RIGHT").assertDoesNotExist()
        compose.onNodeWithContentDescription("Увеличить ширину").assertDoesNotExist()
    }
}
