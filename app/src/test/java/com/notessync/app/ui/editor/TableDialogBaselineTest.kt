package com.notessync.app.ui.editor

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.notessync.app.ui.notes.tables.AddTableDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Isolates the existing dialog from the editor's focus, scrolling and selection toolbar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TableDialogBaselineTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `standalone table dimensions dialog becomes idle and allows creation`() {
        var created = false
        compose.setContent { MaterialTheme { AddTableDialog(onDismiss = {}, onCreate = { _, _ -> created = true }) } }
        compose.onNodeWithText("Новая таблица").assertIsDisplayed()
        compose.onNodeWithText("Добавить").performClick()
        org.junit.Assert.assertTrue(created)
    }
}
