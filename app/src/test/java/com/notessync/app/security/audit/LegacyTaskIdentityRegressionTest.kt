package com.notessync.app.security.audit

import android.app.Application
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.security.*
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Empty syncId is the documented1→2 migration state until first sync; different tasks need isolated grants. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyTaskIdentityRegressionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val models = ViewModelStore()
    @Before fun before() { Dispatchers.setMain(UnconfinedTestDispatcher()); ProtectionSession.lockAll(); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db") }
    @After fun after() { models.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `unlocking one legacy task cannot expose another task with an empty sync identity`() = runBlocking {
        val one = PasswordProtector.create("one fixture password"); val two = PasswordProtector.create("two fixture password")
        assertFalse(PasswordProtector.verify("one fixture password", two))
        var db = AppDatabase.get(app)
        val firstId = db.noteDao().upsert(NoteEntity(syncId = "", title = "One", content = "Secret one", isTask = true,
            isProtected = true, passwordHash = one.hash, passwordSalt = one.salt))
        val secondId = db.noteDao().upsert(NoteEntity(syncId = "", title = "Two", content = "Secret two", isTask = true,
            isProtected = true, passwordHash = two.hash, passwordSalt = two.salt))
        AppDatabase.closeInstance(); db = AppDatabase.get(app) // The persisted state is observed after open, as on upgrade.
        val first = db.noteDao().getById(firstId)!!
        val vm = NotesViewModel(app); models.put("list", vm)
        val done = CompletableDeferred<Boolean>()
        vm.unlockTask(first, "one fixture password") { done.complete(it) }
        assertTrue(withTimeout(15000) { done.await() })
        val rows = db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.All, NoteSort.BY_DATE,
            unlockedTaskIds = ProtectionSession.state.value.taskSyncIds)).first()
        if (rows.any { it.id == secondId }) assertEquals("Secret two", rows.first { it.id == secondId }.content)
        assertEquals("Unlocking one task must reveal only that task", setOf(firstId), rows.map { it.id }.toSet())
    }
}
