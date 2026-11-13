package com.notessync.app.sync

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.sync.auto.*
import com.notessync.app.sync.trust.TrustedPeer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoSyncUiTest {
    @get:Rule val compose = createComposeRule()
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var runtime: AutomaticSyncRuntime
    private lateinit var peers: MemoryPeers
    private val id = UUID.randomUUID().toString()
    @Before fun before() {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).apply { setUiLanguage(UiLanguage.RUSSIAN); setAutoSyncEnabled(true) }
        peers = MemoryPeers(listOf(TrustedPeer(id, "Device B", "ab".repeat(32), revision = UUID.randomUUID().toString())))
    }
    @After fun after() { compose.runOnIdle { if (::runtime.isInitialized) runtime.close() }; SettingsRepository.get(app).setAutoSyncEnabled(true); AppDatabase.closeInstance() }
    @Test fun `settings pause automatic work and forgetting requires an explicit device confirmation`() {
        runtime = AutomaticSyncRuntime(app, peers) { flowOf(null) }; runtime.foreground(true)
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AutoSyncControls(runtime) } } }
        compose.onNodeWithContentDescription("Автосинхронизация").assertIsOn().performClick()
        compose.onNodeWithContentDescription("Автосинхронизация").assertIsOff()
        compose.onNodeWithText("Автообмен выполняется после закрытия редакторов.").assertExists()
        compose.onNodeWithContentDescription("Забыть Device B").performScrollTo().performClick()
        compose.onNodeWithText("Забыть устройство?").assertIsDisplayed()
        assertEquals(1, peers.state.value.peers.size)
        compose.onNodeWithText("Подтвердить").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); peers.state.value.peers.isEmpty() }
        compose.onNodeWithContentDescription("Забыть Device B").assertDoesNotExist()
    }
    @Test fun `foreground settings and manual pause gate network observation and release it on stop`() {
        val opens = AtomicInteger(); val closes = AtomicInteger()
        runtime = AutomaticSyncRuntime(app, peers) { callbackFlow {
            opens.incrementAndGet(); trySend(null); awaitClose { closes.incrementAndGet() }
        } }
        compose.setContent { MaterialTheme { AutoSyncControls(runtime) } }
        compose.runOnIdle { assertEquals(0, opens.get()); runtime.foreground(true) }
        compose.waitUntil(5_000) { compose.waitForIdle(); opens.get() == 1 }
        compose.runOnIdle { runtime.foreground(false) }
        compose.waitUntil(5_000) { compose.waitForIdle(); closes.get() == 1 }
        compose.runOnIdle { runtime.foreground(true) }
        compose.waitUntil(5_000) { compose.waitForIdle(); opens.get() == 2 }
        lateinit var pause: java.io.Closeable
        compose.runOnIdle { pause = runtime.pauseManual() }
        compose.waitUntil(5_000) { compose.waitForIdle(); closes.get() == 2 }
        compose.runOnIdle { pause.close() }
        compose.waitUntil(5_000) { compose.waitForIdle(); opens.get() == 3 }
        compose.onNodeWithContentDescription("Автосинхронизация").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); closes.get() == 3 }
    }
    @Test fun `reset clears local trust disables automatic sync and preserves notes`() {
        SettingsRepository.get(app).setUiLanguage(UiLanguage.ENGLISH)
        val db = AppDatabase.get(app)
        val note = runBlocking(Dispatchers.IO) { NoteEntity(title = "Keep my note", content = "Unchanged").let { it.copy(id = db.noteDao().upsert(it)) } }
        runtime = AutomaticSyncRuntime(app, peers) { flowOf(null) }; runtime.foreground(true)
        app.getSharedPreferences("sync_prefs", 0).edit().putString("device_id", UUID.randomUUID().toString()).commit()
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AutoSyncControls(runtime) } } }
        compose.onNodeWithText("Reset connections").performScrollTo().performClick()
        compose.onNodeWithText("Your notes will remain. QR pairing will be required again.").assertIsDisplayed()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5_000) { compose.waitForIdle(); peers.state.value.peers.isEmpty() && !SettingsRepository.get(app).state.value.autoSyncEnabled }
        assertNull(app.getSharedPreferences("sync_prefs", 0).getString("device_id", null))
        assertEquals(note, runBlocking(Dispatchers.IO) { db.noteDao().getById(note.id) })
    }
}
