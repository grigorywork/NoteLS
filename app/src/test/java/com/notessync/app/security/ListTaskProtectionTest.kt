package com.notessync.app.security

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.model.NoteCardEntry
import com.notessync.app.data.local.model.passwordRecord
import com.notessync.app.settings.BackupFrequency
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real JCA and native Room: list-menu mutations cannot overwrite body or authorize stale/locked rows. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ListTaskProtectionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private lateinit var scope: CoroutineScope
    private lateinit var controller: ListTaskProtection
    private lateinit var original: NoteEntity
    private lateinit var target: NoteCardEntry
    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        controller = ListTaskProtection(app, db, scope)
        val note = NoteEntity(title = "Original", content = "Original body", isTask = true)
        original = note.copy(id = db.noteDao().upsert(note))
        target = NoteCardEntry(id = original.id, syncId = original.syncId, isTask = true)
    }
    @After fun after() { controller.cancel(); scope.cancel(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun change(password: String = "fixture password", remove: Boolean = false, card: NoteCardEntry = target): Boolean {
        val done = CompletableDeferred<Boolean>()
        controller.change(card, password, remove) { done.complete(it) }
        return withTimeout(15_000) { done.await() }
    }
    @Test fun `protecting a stale card preserves fresh text and increments the fresh version`() = runBlocking {
        val fresh = original.copy(title = "New title", content = "New body", textFormat = "", updatedAt = original.updatedAt + 100)
        db.noteDao().update(fresh)
        assertTrue(change())
        val saved = db.noteDao().getById(original.id)!!
        assertEquals(fresh.content, saved.content); assertEquals(fresh.title, saved.title)
        assertTrue(saved.updatedAt > fresh.updatedAt); assertTrue(saved.dirty)
        assertTrue(saved.isProtected)
        assertTrue(PasswordProtector.verify("fixture password", db.noteDao().getTaskProtection(saved.id)!!.passwordRecord()!!))
        assertTrue(ProtectionSession.isUnlocked(saved))
    }
    @Test fun `removing protection requires current password and retains content`() = runBlocking {
        assertTrue(change())
        val protected = db.noteDao().getById(original.id)!!
        assertFalse(change(password = "wrong password", remove = true))
        assertEquals(protected.passwordHash, db.noteDao().getById(original.id)!!.passwordHash)
        assertTrue(change(remove = true))
        val saved = db.noteDao().getById(original.id)!!
        assertFalse(saved.isProtected); assertNull(saved.passwordHash); assertNull(saved.passwordSalt)
        assertEquals(original.content, saved.content)
    }
    @Test fun `reused row identity and trashed records cannot acquire protection`() = runBlocking {
        assertFalse(change(card = target.copy(syncId = "stale-identity")))
        db.noteDao().update(original.copy(isDeleted = true))
        assertFalse(change())
        assertFalse(db.noteDao().getById(original.id)!!.isProtected)
    }
    @Test fun `protected parent cannot be bypassed by list-menu password setup`() = runBlocking {
        val record = PasswordProtector.create("parent fixture password")
        val bookId = db.notebookDao().upsert(NotebookEntity(name = "Closed", isProtected = true,
            passwordHash = record.hash, passwordSalt = record.salt))
        db.noteDao().update(original.copy(notebookId = bookId))
        assertFalse(change())
        assertFalse(db.noteDao().getById(original.id)!!.isProtected)
    }
    @Test fun `ordinary notes do not silently receive task-only protection`() = runBlocking {
        db.noteDao().update(original.copy(isTask = false))
        assertFalse(change())
        assertFalse(db.noteDao().getById(original.id)!!.isProtected)
    }
}
