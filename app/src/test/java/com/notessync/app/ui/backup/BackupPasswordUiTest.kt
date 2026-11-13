package com.notessync.app.ui.backup

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupPasswordUiTest {
    // Match real navigation: attach the Activity content before opening its modal window.
    @Composable private fun Host(content: @Composable () -> Unit) {
        var shown by rememberSaveable { mutableStateOf(false) }
        NoteSyncTheme { Box(Modifier.requiredSize(320.dp, 760.dp)) {
            TextButton(onClick = { shown = true }) { Text("Открыть диалог") }
            if (shown) content()
        } }
    }
    @get:Rule val compose = createComposeRule()
    @Test fun `setup masks input requires matching minimum password and submits only on explicit action`() {
        var result: Pair<String,String>? = null
        compose.setContent { Host { BackupPasswordDialog(BackupPasswordMode.SETUP, false, false, {}, { a,b -> result = String(a) to String(b); a.fill('\u0000'); b.fill('\u0000') }, {}) } }
        compose.onNodeWithText("Открыть диалог").performClick()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Новый пароль").performScrollTo().performTextInput("short")
        compose.onNodeWithText("Повторите пароль").performScrollTo().performTextInput("different")
        compose.onNodeWithText("Пароли не совпадают").assertExists()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Новый пароль").performScrollTo().performTextReplacement("a valid password")
        compose.onNodeWithText("Повторите пароль").performScrollTo().performTextReplacement("a valid password")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).assertCountEquals(2)
        assertNull(result)
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("" to "a valid password", result)
    }
    @Test fun `passwords are not restored across saved state recreation and fingerprint keeps password fallback`() {
        var fingerprint = 0
        val restore = StateRestorationTester(compose)
        restore.setContent { Host { BackupPasswordDialog(BackupPasswordMode.UNLOCK, false, true, { fingerprint++ }, { _,_ -> }, {}) } }
        compose.onNodeWithText("Открыть диалог").performClick()
        compose.onNodeWithText("Пароль").performTextInput("do not retain this")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
        compose.onNodeWithText("Открыть по отпечатку").performClick(); assertEquals(1, fingerprint)
        compose.onNodeWithText("Пароль").assertExists()
    }
}
