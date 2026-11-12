package com.notessync.app.widget

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QuickTaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var app: Application
    private lateinit var vm: QuickTaskViewModel
    private lateinit var state: SavedStateHandle
    @Before fun before() {
        Dispatchers.setMain(dispatcher); app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        state = SavedStateHandle(mapOf("dayKey" to LocalDate.now().plusDays(5).toEpochDay()))
        vm = QuickTaskViewModel(app, state); models.put("quick", vm); dispatcher.scheduler.runCurrent()
    }
    @After fun after() = runTest(dispatcher) {
        models.clear()
        // NonCancellable commits must finish before their test dispatcher and database disappear.
        vm.persist()
        AppDatabase.closeInstance()
        Dispatchers.resetMain()
    }
    private fun count(): Int = AppDatabase.get(app).openHelper.readableDatabase.query("SELECT COUNT(*) FROM notes WHERE isDeleted = 0").use { it.moveToFirst(); it.getInt(0) }

    @Test fun `plus flushes exactly one task and close saves the next with the chosen date`() = runTest(dispatcher) {
        vm.changeText("First"); assertTrue(vm.persist()); val firstId = vm.currentId
        assertTrue(vm.persist()); vm.next().join(); assertEquals("", vm.text)
        assertEquals(1, count())
        vm.changeText("Second"); var closed = false
        vm.close { closed = true }.join()
        assertTrue(closed); assertEquals(2, count())
        val dao = AppDatabase.get(app).noteDao()
        assertEquals("First", dao.getById(firstId)!!.content)
        assertEquals(state.get<Long>("quickDay"), dao.getById(vm.currentId)!!.dayKey)
    }

    @Test fun `failed plus preserves text and lifecycle stop completes even when view model is cleared`() = runTest(dispatcher) {
        val db = AppDatabase.get(app)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_quick BEFORE INSERT ON notes BEGIN SELECT RAISE(ABORT, 'test'); END")
        vm.changeText("Do not lose this"); var startedNext = false
        vm.next { startedNext = true }.join()
        assertFalse(startedNext); assertEquals("Do not lose this", vm.text); assertNotNull(vm.error)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_quick")
        val job = vm.onStopped(); models.clear(); job.join()
        assertEquals(1, count()); assertEquals("Do not lose this", db.noteDao().getBySyncId(state.get<String>("quickIdentity")!!)!!.content)
    }

    @Test fun `restored draft with a missing local id reuses its persistent sync identity`() = runTest(dispatcher) {
        vm.changeText("Before recreation"); assertTrue(vm.persist()); val id = vm.currentId
        val restored = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) } + ("quickId" to 0L))
        vm = QuickTaskViewModel(app, restored); models.put("quick", vm)
        vm.changeText("After recreation"); assertTrue(vm.persist())
        assertEquals(id, vm.currentId); assertEquals(1, count())
        assertEquals("After recreation", AppDatabase.get(app).noteDao().getById(id)!!.content)
    }

    @Test fun `clearing a saved draft removes the row and later typing starts a new identity`() = runTest(dispatcher) {
        vm.changeText("Old"); assertTrue(vm.persist()); val id = vm.currentId
        vm.changeText(""); assertTrue(vm.persist()); assertEquals(0, count())
        vm.changeText("New"); assertTrue(vm.persist()); assertEquals(1, count())
        assertNotEquals(id, vm.currentId)
    }

    @Test fun `date change preserves local reminder time and invalid input leaves the draft intact`() = runTest(dispatcher) {
        val date = LocalDate.now().plusDays(5); val zone = ZoneId.systemDefault()
        vm.changeText("Keep")
        assertTrue(vm.setReminder(date.atTime(13, 45).atZone(zone).toInstant().toEpochMilli()))
        vm.changeDay(date.plusDays(1).toEpochDay())
        assertEquals(date.plusDays(1).atTime(13, 45), Instant.ofEpochMilli(vm.reminderAt!!).atZone(zone).toLocalDateTime())
        val selected = vm.reminderAt; assertFalse(vm.setReminder(System.currentTimeMillis() - 1)); assertEquals(selected, vm.reminderAt)
        vm.changeText("x".repeat(QuickTaskViewModel.MAX_TEXT + 1)); assertEquals("Keep", vm.text)
    }
}
