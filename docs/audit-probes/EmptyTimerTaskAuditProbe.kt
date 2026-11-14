package com.notessync.app.ui.notes.audit

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.reminders.createReminderChannel
import com.notessync.app.settings.*
import com.notessync.app.ui.notes.NoteEditorViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Chosen clocks are meaningful task state even before a title; actual editor/Room exit path, not a phone gesture. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EmptyTimerTaskAuditProbe {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val models = ViewModelStore()
    private lateinit var vm: NoteEditorViewModel
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL); app.createReminderChannel()
        vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("isTask" to true))); models.put("editor", vm)
        withTimeout(15000) { while (!vm.isReady) delay(2) }
    }
    @After fun after() { models.clear(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `countdown-only task survives empty draft cleanup on editor exit`() = runBlocking {
        assertTrue(vm.startCountdownUntil(System.currentTimeMillis() + 3600000))
        assertTrue(vm.flushOnExit())
        val saved = AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertFalse("A chosen running countdown must not be discarded as an empty draft", saved.isDeleted)
        assertNotNull(saved.taskCountdownEndAt)
    }
    @Test fun `running stopwatch-only task survives empty draft cleanup on editor exit`() = runBlocking {
        vm.toggleTaskTimer(); assertTrue(vm.flushOnExit())
        val saved = AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!
        assertFalse("A running stopwatch must not be discarded as an empty draft", saved.isDeleted)
        assertNotNull(saved.taskTimerStartedAt)
    }
}
