package com.notessync.app.notes.diary

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.data.local.model.NoteIdentity
import com.notessync.app.data.local.model.nextChangeTimestamp
import com.notessync.app.security.PasswordRecord
import com.notessync.app.security.ProtectionSession

/** Bounded metadata for the private destination; a separately protected entry has no preview. */
data class DiaryRow(val id: Long, val syncId: String, val title: String, val preview: String,
    val dayKey: Long?, val updatedAt: Long, val isDeleted: Boolean, val isProtected: Boolean)

data class DiaryCandidate(val id: Long, val syncId: String, val title: String, val legacyTag: Boolean,
    val dayKey: Long?, val updatedAt: Long, val isDeleted: Boolean = false, val isTask: Boolean = false)

internal object PrivateDiary {
    const val PAGE = 200
    const val MAX_ROWS = PAGE
    private const val TAGGED = "EXISTS (SELECT 1 FROM note_tag_cross_ref r JOIN tags t ON t.id=r.tagId WHERE r.noteId=notes.id AND t.name='Дневник')"

    suspend fun create(db: AppDatabase, record: PasswordRecord, now: Long = System.currentTimeMillis()): NotebookEntity {
        require(DiaryPrivacy.validCredentials(record.hash, record.salt))
        return db.withTransaction {
            val book = NotebookEntity(name = "Дневник", isDiary = true, isProtected = true,
                passwordHash = record.hash, passwordSalt = record.salt, createdAt = now, updatedAt = now)
            book.copy(id = db.notebookDao().upsert(book))
        }
    }

    suspend fun changePassword(db: AppDatabase, expected: NotebookEntity, old: String, replacement: String,
        canProceed: () -> Boolean = { true }): Boolean {
        if (!DiaryPrivacy.valid(expected) || !ProtectionSession.isUnlocked(expected) || !canProceed() ||
            replacement.length !in com.notessync.app.security.MIN_PASSWORD_LENGTH..com.notessync.app.security.MAX_PASSWORD_LENGTH) return false
        val oldRecord = PasswordRecord(expected.passwordHash ?: return false, expected.passwordSalt ?: return false)
        val verified = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.notessync.app.security.PasswordProtector.verify(old, oldRecord)
        }
        if (!verified || !canProceed()) return false
        val record = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.notessync.app.security.PasswordProtector.create(replacement)
        }
        return db.withTransaction {
            val fresh = db.notebookDao().getById(expected.id)
            if (fresh != expected || !canProceed() || !ProtectionSession.isUnlocked(expected)) false else {
                db.notebookDao().update(expected.copy(passwordHash = record.hash, passwordSalt = record.salt,
                    updatedAt = nextChangeTimestamp(expected.updatedAt), dirty = true))
                DiarySession.lock(expected.id)
                true
            }
        }
    }

    fun rows(bookId: Long, trash: Boolean, page: Int) = SimpleSQLiteQuery(
        "SELECT notes.id, notes.syncId, CASE WHEN notes.isProtected=0 THEN SUBSTR(notes.title,1,256) ELSE '' END AS title, " +
            "CASE WHEN notes.isProtected=0 THEN SUBSTR(notes.content,1,160) ELSE '' END AS preview, notes.dayKey, " +
            "notes.updatedAt, notes.isDeleted, notes.isProtected FROM notes JOIN notebooks b ON b.id=notes.notebookId " +
            "WHERE b.id=? AND b.isDiary=1 AND b.isProtected=1 AND b.isDeleted=0 AND notes.isPurged=0 AND notes.isDeleted=? " +
            "ORDER BY COALESCE(notes.dayKey, notes.createdAt/86400000) DESC, notes.updatedAt DESC, notes.id DESC LIMIT ? OFFSET ?",
        arrayOf(bookId, if (trash) 1 else 0, PAGE + 1, page.coerceAtLeast(0).toLong() * PAGE))

    fun candidates(unlockedBookIds: Set<Long>, page: Int, unlockedTaskIds: Set<String> = emptySet(), includeTasks: Boolean = false): SimpleSQLiteQuery {
        val ids = unlockedBookIds.take(256)
        val tasks = unlockedTaskIds.take(256)
        val bookAccess = if (ids.isEmpty()) "b.isProtected=0" else "(b.isProtected=0 OR b.id IN (${ids.joinToString(",") { "?" }}))"
        val taskAccess = if (tasks.isEmpty()) "notes.isProtected=0" else "(notes.isProtected=0 OR (notes.syncId!='' AND notes.syncId IN (${tasks.joinToString(",") { "?" }})))"
        val datedType = if (includeTasks) "1=1" else "notes.isTask=0"
        val sql = "SELECT notes.id, notes.syncId, SUBSTR(notes.title,1,256) AS title, $TAGGED AS legacyTag, notes.dayKey, notes.updatedAt, notes.isDeleted, notes.isTask " +
            "FROM notes LEFT JOIN notebooks b ON b.id=notes.notebookId WHERE notes.isPurged=0 AND (notes.isDeleted=0 OR $TAGGED) AND $taskAccess " +
            "AND (notes.notebookId IS NULL OR (b.id IS NOT NULL AND b.isDiary=0 AND b.isDeleted=0 AND $bookAccess)) " +
            "AND ($TAGGED OR (notes.dayKey IS NOT NULL AND notes.isDeleted=0 AND $datedType)) " +
            "ORDER BY legacyTag DESC, notes.updatedAt DESC, notes.id DESC LIMIT ? OFFSET ?"
        val args = tasks.map { it as Any } + ids.map { it as Any } + listOf(PAGE + 1, page.coerceAtLeast(0).toLong() * PAGE)
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** One transaction changes only membership/version; IDs, documents, tags and files stay intact. */
    suspend fun move(db: AppDatabase, expectedBook: NotebookEntity, selected: List<NoteIdentity>, includeTasks: Boolean = false): Int = db.withTransaction {
        require(selected.size <= MAX_ROWS)
        val target = db.notebookDao().getById(expectedBook.id)
        check(target != null && target == expectedBook && DiaryPrivacy.valid(target) && ProtectionSession.isUnlocked(target))
        var moved = 0
        for (batch in selected.distinctBy { it.id }.chunked(200)) {
            val expected = batch.associate { it.id to it.syncId }
            for (note in db.noteDao().getByIds(batch.map { it.id })) {
                if (expected[note.id] != note.syncId || note.isPurged || !ProtectionSession.isUnlocked(note)) continue
                val parent = note.notebookId?.let { db.notebookDao().getById(it) }
                if (note.notebookId != null && (parent == null || parent.isDiary || parent.isDeleted || !ProtectionSession.isUnlocked(parent))) continue
                val tagged = db.tagDao().getTagNamesForNote(note.id).any { it == DiaryEntries.TAG_NAME }
                if (!tagged && (note.isDeleted || note.dayKey == null || (note.isTask && !includeTasks))) continue
                db.noteDao().update(note.copy(notebookId = target.id, updatedAt = nextChangeTimestamp(note.updatedAt), dirty = true))
                moved++
            }
        }
        moved
    }
}
