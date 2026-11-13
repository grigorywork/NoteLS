package com.notessync.app.sync

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.NoteSyncApp
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.*
import com.notessync.app.sync.auto.AutomaticSyncRuntime
import com.notessync.app.sync.core.SyncWorkGate
import com.notessync.app.ui.notes.NoteEditorViewModel
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = NoteSyncApp::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorSyncPauseTest {
    private val dispatcher = StandardTestDispatcher(); private val models = ViewModelStore()
    private val app: NoteSyncApp get() = RuntimeEnvironment.getApplication() as NoteSyncApp
    @Before fun before() { Dispatchers.setMain(dispatcher); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL) }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); AutomaticSyncRuntime.existing(app)?.close(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `editor waits for accepted sync before loading then pauses auto until its pending save completes`() = runTest(dispatcher) {
        val db = AppDatabase.get(app); val note = NoteEntity(content = "Before", updatedAt = 100).let { it.copy(id = db.noteDao().upsert(it)) }
        val initialPauses = SyncWorkGate.pauses.value
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val incoming = launch(Dispatchers.IO) { SyncWorkGate.transfer(automatic = false) {
            entered.complete(Unit); release.await(); db.noteDao().update(note.copy(content = "Received before loading", updatedAt = 200, dirty = false))
        } }
        entered.await()
        val editor = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id))); models.put("editor", editor)
        dispatcher.scheduler.runCurrent(); assertFalse(editor.isReady); assertEquals(initialPauses + 1, SyncWorkGate.pauses.value)
        release.complete(Unit); incoming.join()
        withContext(Dispatchers.IO) { withTimeout(5_000) { while (!editor.isReady) delay(5) } }
        assertEquals("Received before loading", editor.contentField.text)
        editor.onContentChange(TextFieldValue("Final local text")); editor.saveNow(force = true)
        models.clear(); dispatcher.scheduler.runCurrent()
        withContext(Dispatchers.IO) { withTimeout(5_000) { while (SyncWorkGate.pauses.value != initialPauses) delay(5) } }
        assertEquals("Final local text", db.noteDao().getById(note.id)!!.content)
    }
}
