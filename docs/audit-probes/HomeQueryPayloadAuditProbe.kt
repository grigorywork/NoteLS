package com.notessync.app.notes.audit

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.text.notePreviewText
import com.notessync.app.security.PasswordProtector
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Payload witness, not a phone memory/latency benchmark and not an intentionally failing contract test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class HomeQueryPayloadAuditProbe {
    @Before fun before() { AppDatabase.closeInstance(); RuntimeEnvironment.getApplication().deleteDatabase("notessync.db") }
    @After fun after() { AppDatabase.closeInstance() }
    @Test fun `record actual text loaded for twenty cards and a locked task placeholder`() = runBlocking {
        val db = AppDatabase.get(RuntimeEnvironment.getApplication())
        val body = "x".repeat(200000)
        var table = NoteTables().add("witness", 2, 7)
        repeat(13) { i -> table = table.editCell(TableCellRef("witness", i / 7, i % 7), "t".repeat(if (i < 12) 4000 else 2000)) }
        val raw = table.encode(); assertNotNull(NoteTables.decode(raw))
        val ids = (1..20).map { db.noteDao().upsert(NoteEntity(title = "Fixture $it", content = body, tablesData = raw)) }
        val password = PasswordProtector.create("fixture only password")
        db.noteDao().upsert(NoteEntity(title = "Protected", content = body, tablesData = raw, isTask = true,
            isProtected = true, passwordHash = password.hash, passwordSalt = password.salt))
        val cards = db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.All, NoteSort.BY_DATE)).first()
        val locked = db.noteDao().rawQuery(NotesViewModel.buildLockedTasksQuery()).first()
        assertEquals(20, cards.size); assertEquals(1, locked.size)
        val full = cards.sumOf { it.content.length.toLong() }
        val tables = cards.sumOf { it.tablesData.length.toLong() }
        val shown = cards.sumOf { notePreviewText(it.content).length.toLong() }
        assertEquals(4000000L, full); assertEquals(raw.length * 20L, tables); assertEquals(40000L, shown)
        assertEquals(200000, locked.single().content.length)
        assertEquals(body, db.noteDao().getById(ids.first())!!.content)
        println("WITNESS_UTF16_CHARS fullBody=$full tables=$tables cardPrefixes=$shown lockedBody=${locked.single().content.length}")
    }
}
