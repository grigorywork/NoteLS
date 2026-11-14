package com.notessync.app.notes.audit

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.text.*
import com.notessync.app.security.*
import com.notessync.app.settings.*
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

/** Actual SQL result sizes and preservation checks; not heap, latency or physical phone measurements. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeQueryProjectionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher()); AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        ProtectionSession.lockAll(); SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    @Test fun `twenty card rows load only prefixes and table flags while closed targets load no private payload`() = runBlocking {
        val body = "x".repeat(200000)
        var table = NoteTables().add("fixture", 2, 7)
        repeat(13) { i -> table = table.editCell(TableCellRef("fixture", i / 7, i % 7), "t".repeat(if (i < 12) 4000 else 2000)) }
        val raw = table.encode(); assertNotNull(NoteTables.decode(raw))
        val ids = (1..20).map { db.noteDao().upsert(NoteEntity(title = "Fixture $it", content = body, tablesData = raw)) }
        val password = PasswordProtector.create("fixture only password")
        db.noteDao().upsert(NoteEntity(title = "SECRET", content = body, tablesData = raw, isTask = true,
            isProtected = true, passwordHash = password.hash, passwordSalt = password.salt))
        val query = NotesViewModel.buildCardQuery("", NoteFilter.All, NoteSort.BY_DATE)
        val cards = db.noteDao().rawCards(query).first()
        assertEquals(20, cards.size); assertEquals(40000L, cards.sumOf { it.content.length.toLong() })
        assertTrue(cards.all { it.hasTables })
        db.openHelper.readableDatabase.query(query).use {
            assertFalse(it.columnNames.any { name -> name in setOf("tablesData", "passwordHash", "passwordSalt") })
        }
        val locked = NotesViewModel.buildLockedTaskTargetsQuery()
        assertEquals(1, db.noteDao().rawLockedTaskTargets(locked).first().size)
        db.openHelper.readableDatabase.query(locked).use {
            assertEquals(setOf("id", "syncId", "notebookId", "isDeleted"), it.columnNames.toSet())
        }
        assertEquals(body, db.noteDao().getById(ids.first())!!.content)
        assertEquals(raw, db.noteDao().getById(ids.first())!!.tablesData)
    }
    @Test fun `search sees the full tail and card actions preserve the complete document`() = runBlocking {
        val text = "x".repeat(200000) + " UNIQUE_TAIL_NEEDLE"
        val table = NoteTables().add("fixture", 2, 2).encode()
        val format = TextFormatting().apply(0, text.length, FormatKind.BOLD, "1").encode()
        val note = NoteEntity(title = "Task fixture", content = text, textFormat = format, tablesData = table, isTask = true)
            .let { it.copy(id = db.noteDao().upsert(it)) }
        val card = db.noteDao().rawCards(NotesViewModel.buildCardQuery("UNIQUE_TAIL_NEEDLE", NoteFilter.All, NoteSort.BY_DATE)).first().single()
        assertEquals(note.id, card.id); assertEquals(2000, card.content.length); assertFalse(card.content.contains("NEEDLE"))
        val vm = NotesViewModel(app); models.put("home", vm)
        val parent = vm.viewModelScope.coroutineContext[Job]!!; val existing = parent.children.toSet()
        vm.toggleCompleted(card)
        withTimeout(15000) { parent.children.filter { it !in existing }.toList().joinAll() }
        val after = db.noteDao().getById(note.id)!!
        assertTrue(after.isCompleted); assertEquals(text, after.content); assertEquals(table, after.tablesData); assertEquals(format, after.textFormat)
    }
    @Test fun `SQLite codepoint prefixes remain bounded and visible clipping preserves whole emoji`() = runBlocking {
        val text = "😀".repeat(3000)
        val id = db.noteDao().upsert(NoteEntity(title = "Unicode fixture", content = text))
        val card = db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.All, NoteSort.BY_DATE)).first().single()
        assertEquals(4000, card.content.length)
        val visible = notePreviewText(card.content)
        assertEquals(2000, visible.length); assertTrue(visible.last().isLowSurrogate())
        assertEquals(text, db.noteDao().getById(id)!!.content)
    }
}
