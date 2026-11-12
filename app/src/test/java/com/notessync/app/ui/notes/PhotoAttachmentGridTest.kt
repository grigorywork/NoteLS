package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.data.local.entity.AttachmentEntity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoAttachmentGridTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `forty attachments compose only a viewport before and after scrolling`() {
        val photos = (1L..40L).map { AttachmentEntity(id = it, noteId = 1, filePath = "missing_$it", mimeType = "image/jpeg", sizeBytes = 1) }
        compose.setContent { MaterialTheme { PhotoAttachmentGrid(photos, {}, {}, Modifier.requiredWidth(320.dp).testTag("photos")) } }
        assertTrue(compose.onAllNodesWithContentDescription("Фото заметки").fetchSemanticsNodes().size in 1..6)
        compose.onNodeWithTag("photos").performScrollToIndex(39)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithContentDescription("Фото заметки").fetchSemanticsNodes().size in 1..6)
    }
}
