package com.notessync.app.ui.notes

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.notessync.app.settings.VoiceControl
import com.notessync.app.settings.VoiceEngine
import com.notessync.app.ui.theme.NoteSyncTheme
import com.notessync.app.voice.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceInputButtonTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(VoiceState.IDLE)
    private lateinit var callbacks: VoiceCallbacks
    private var starts = 0; private var finishes = 0; private var destroys = 0
    private val inserted = mutableListOf<String>()
    private val session = VoiceSession(object : VoiceRecognizerFactory {
        override fun available(engine: VoiceEngine) = true
        override fun create(engine: VoiceEngine, callbacks: VoiceCallbacks): VoiceRecognizer {
            this@VoiceInputButtonTest.callbacks = callbacks
            return object : VoiceRecognizer {
                override fun start() { starts++ }
                override fun finish() { finishes++ }
                override fun cancel() = Unit
                override fun destroy() { destroys++ }
            }
        }
    }, VoiceDeadline { _, _ -> {} }, object : VoiceSession.Listener {
        override fun state(state: VoiceState) { this@VoiceInputButtonTest.state = state }
        override fun partial(text: String) = Unit
        override fun result(text: String) { inserted += text }
        override fun error(failure: VoiceFailure) = Unit
    })
    @After fun after() { compose.runOnIdle { session.cancel() } }
    private fun content(control: VoiceControl) { compose.setContent {
        NoteSyncTheme { VoiceInputButton(control, state, { session.start(VoiceEngine.ONLINE) }, session::finish, session::cancel) }
    } }
    @Test fun `holding starts on down and release waits for final without a second click session`() {
        content(VoiceControl.HOLD)
        compose.onNodeWithTag("voice-input").performTouchInput { down(center); advanceEventTime(600) }
        compose.runOnIdle { assertEquals(1, starts); callbacks.ready() }
        compose.onNodeWithTag("voice-input").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, finishes); assertEquals(0, destroys); assertTrue(inserted.isEmpty()) }
        compose.onNodeWithContentDescription("Отменить распознавание").assertExists()
        compose.runOnIdle { callbacks.result("Text after release"); assertEquals(listOf("Text after release"), inserted); assertEquals(1, destroys) }
    }
    @Test fun `gesture cancellation discards the recognizer and its late result`() {
        content(VoiceControl.HOLD)
        compose.onNodeWithTag("voice-input").performTouchInput { down(center); advanceEventTime(200) }
        compose.onNodeWithTag("voice-input").performTouchInput { cancel() }
        compose.runOnIdle { callbacks.result("Cancelled text"); assertEquals(0, finishes); assertEquals(1, destroys); assertTrue(inserted.isEmpty()) }
    }
    @Test fun `tap and accessibility click can start finish and cancel without holding`() {
        content(VoiceControl.TAP)
        compose.onNodeWithTag("voice-input").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, starts); callbacks.ready() }
        compose.onNodeWithTag("voice-input").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, finishes); assertEquals(0, destroys) }
        compose.onNodeWithTag("voice-input").performClick()
        compose.runOnIdle { assertEquals(1, destroys); assertEquals(VoiceState.IDLE, state) }
    }
}
