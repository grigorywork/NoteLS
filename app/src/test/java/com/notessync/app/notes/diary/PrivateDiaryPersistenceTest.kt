package com.notessync.app.notes.diary

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.backup.EncryptedBackupFixture
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.data.local.model.NoteIdentity
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.security.*
import com.notessync.app.security.biometrics.*
import com.notessync.app.settings.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.sync.protocol.*
import com.notessync.app.ui.notes.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.ByteArrayOutputStream
import java.io.File
import javax.crypto.Cipher

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PrivateDiaryPersistenceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val models = ViewModelStore()
    @Before fun before() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); DiarySession.lock(); ProtectionSession.lockAll()
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
    }
    @After fun after() { models.clear(); DiarySession.lock(); ProtectionSession.lockAll(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private suspend fun book() = PrivateDiary.create(db, PasswordProtector.create("test-only-diary-password"), 1).also {
        DiarySession.register(it.id); ProtectionSession.unlockNotebook(it.id)
    }
    private suspend fun insert(note: NoteEntity) = note.copy(id = db.noteDao().upsert(note))
    private suspend fun tag(note: NoteEntity) {
        val id = db.tagDao().getByName(DiaryEntries.TAG_NAME)?.id ?: db.tagDao().insert(TagEntity(name = DiaryEntries.TAG_NAME))
        db.tagDao().attachToNote(NoteTagCrossRef(note.id, id))
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(10) }

    @Test fun `real schema18 migrates to19 without assigning a password or moving any legacy record`() = runBlocking {
        val name = "schemas/com.notessync.app.data.local.AppDatabase/18.json"
        val schema = JSONObject(listOf(File(name), File("app/$name")).first { it.isFile }.readText()).getJSONObject("database")
        val path = app.getDatabasePath("notessync.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.getJSONArray("indices")
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries"); for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            fun values(table: String) = ContentValues().also { result -> old.rawQuery("PRAGMA table_info($table)", null).use { c -> while (c.moveToNext()) {
                if (c.getInt(3) != 0 || c.getInt(5) != 0) {
                    val field = c.getString(1)
                    if (c.getString(2) == "TEXT") result.put(field, when (field) { "syncId" -> "legacy-$table"; "title", "name" -> "Legacy"; "content" -> "Original 😀"; else -> "" })
                    else result.put(field, if (field == "id") 1L else 0L)
                }
            } } }
            old.insertOrThrow("notebooks", null, values("notebooks"))
            old.insertOrThrow("notes", null, values("notes").apply { put("notebookId", 1); put("dayKey", 20_000L); put("taskElapsedMillis", 77) })
            old.version = 18
        }
        val migrated = db.noteDao().getById(1)!!
        assertEquals(19, db.openHelper.readableDatabase.version)
        assertFalse(db.notebookDao().getById(1)!!.isDiary); assertFalse(db.notebookDao().getById(1)!!.isProtected)
        assertEquals("Original 😀", migrated.content); assertEquals("legacy-notes", migrated.syncId)
        assertEquals(1L, migrated.notebookId); assertEquals(20_000L, migrated.dayKey); assertEquals(77L, migrated.taskElapsedMillis)
    }

    @Test fun `all general queries and cached cards exclude diary even with both password grants`() = runBlocking {
        val book = book()
        val note = insert(NoteEntity(notebookId = book.id, title = "SECRET", content = "Private", isTask = true,
            isCompleted = true, isFavorite = true, isProtected = true, taskDueAt = 1))
        tag(note); ProtectionSession.unlockTask(note.syncId)
        for (filter in listOf(NoteFilter.All, NoteFilter.Tasks, NoteFilter.Completed, NoteFilter.Unfinished,
            NoteFilter.Overdue, NoteFilter.Favorites, NoteFilter.Notebook(book.id, book.name), NoteFilter.Tag(DiaryEntries.TAG_NAME))) {
            val query = NotesViewModel.buildQuery("", filter, NoteSort.BY_DATE, setOf(book.id), setOf(note.syncId))
            assertTrue(db.noteDao().rawQuery(query).first().isEmpty())
            assertTrue(db.noteDao().rawCards(NotesViewModel.buildCardQuery("", filter, NoteSort.BY_DATE, setOf(book.id), setOf(note.syncId))).first().isEmpty())
        }
        assertTrue(visibleNotes(listOf(note), listOf(book), ProtectionSession.state.value).isEmpty())
        val private = db.noteDao().diaryRows(PrivateDiary.rows(book.id, false, 0)).first().single()
        assertEquals(note.id, private.id); assertEquals("", private.title); assertEquals("", private.preview)
        db.noteDao().update(note.copy(isDeleted = true))
        assertTrue(db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.Trash, NoteSort.BY_DATE, setOf(book.id), setOf(note.syncId))).first().isEmpty())
        assertTrue(db.noteDao().rawLockedTaskTargets(NotesViewModel.buildLockedTaskTargetsQuery(setOf(book.id), trash = true)).first().isEmpty())
    }

    @Test fun `selected legacy migration preserves full documents identities attachments and unselected calendar records`() = runBlocking {
        val book = book()
        val original = insert(NoteEntity(title = "Old journal", content = "Rich 😀 body", textFormat = "opaque-format",
            tablesData = "opaque-tables", dayKey = 20_000, createdAt = 100, updatedAt = 200, dirty = false, isFavorite = true))
        tag(original)
        val dated = insert(NoteEntity(title = "Calendar note", dayKey = 20_000))
        val task = insert(NoteEntity(title = "Calendar task", dayKey = 20_000, isTask = true))
        val file = File(app.filesDir, "attachments/diary-preserved").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1,2,3,4)) }
        val photo = AttachmentEntity(noteId = original.id, filePath = file.path, mimeType = "image/jpeg", sizeBytes = 4)
            .let { it.copy(id = db.attachmentDao().upsert(it)) }
        val candidates = db.noteDao().diaryCandidates(PrivateDiary.candidates(emptySet(), 0)).first()
        assertTrue(candidates.single { it.id == original.id }.legacyTag)
        assertFalse(candidates.single { it.id == dated.id }.legacyTag); assertFalse(candidates.any { it.id == task.id })
        assertEquals(1, PrivateDiary.move(db, book, listOf(NoteIdentity(original.id, original.syncId), NoteIdentity(task.id, task.syncId))))
        val after = db.noteDao().getById(original.id)!!
        assertEquals(original.copy(notebookId = book.id, dirty = true, updatedAt = after.updatedAt), after); assertTrue(after.updatedAt > original.updatedAt)
        assertEquals(photo, db.attachmentDao().getByNoteId(original.id).single()); assertArrayEquals(byteArrayOf(1,2,3,4), file.readBytes())
        assertEquals(listOf(DiaryEntries.TAG_NAME), db.tagDao().getTagNamesForNote(original.id))
        assertEquals(dated, db.noteDao().getById(dated.id)); assertEquals(task, db.noteDao().getById(task.id))
        assertEquals(0, PrivateDiary.move(db, book, listOf(NoteIdentity(original.id, original.syncId))))
    }

    @Test fun `stale identities source locks and changed target credentials cannot move or overwrite records`() = runBlocking {
        val book = book(); val parent = NotebookEntity(name = "Source", isProtected = true).let { it.copy(id = db.notebookDao().upsert(it)) }
        val locked = insert(NoteEntity(notebookId = parent.id, title = "Locked source", dayKey = 1))
        val old = insert(NoteEntity(title = "Old identity", dayKey = 1))
        val replacement = old.copy(syncId = "replacement-identity", content = "Fresh")
        db.noteDao().update(replacement)
        assertEquals(0, PrivateDiary.move(db, book, listOf(NoteIdentity(old.id, old.syncId), NoteIdentity(locked.id, locked.syncId))))
        assertEquals(locked, db.noteDao().getById(locked.id)); assertEquals(replacement, db.noteDao().getById(old.id))
        val changed = book.copy(updatedAt = 2); db.notebookDao().update(changed)
        assertTrue(runCatching { PrivateDiary.move(db, book, listOf(NoteIdentity(old.id, replacement.syncId))) }.isFailure)
        assertEquals(replacement, db.noteDao().getById(old.id))
    }

    @Test fun `general trash actions cannot purge unlocked private trash and scoped restore retains membership`() = runBlocking {
        val book = book(); val note = insert(NoteEntity(title = "Private trash", notebookId = book.id, isDeleted = true))
        val actions = NoteActions(app, db)
        assertEquals(0, actions.apply(null, NoteActions.Action.PURGE))
        assertEquals(0, actions.apply(setOf(note.id), NoteActions.Action.PURGE, mapOf(note.id to note.syncId)))
        assertEquals(note, db.noteDao().getById(note.id))
        assertEquals(1, actions.apply(setOf(note.id), NoteActions.Action.RESTORE, mapOf(note.id to note.syncId), book.id))
        assertFalse(db.noteDao().getById(note.id)!!.isDeleted); assertEquals(book.id, db.noteDao().getById(note.id)!!.notebookId)
    }

    @Test fun `private pages are bounded but later pages remain accessible without loading full bodies`() = runBlocking {
        val book = book()
        for (i in 1..205) insert(NoteEntity(title = "Entry $i", content = "x".repeat(300), notebookId = book.id, updatedAt = i.toLong()))
        val first = db.noteDao().diaryRows(PrivateDiary.rows(book.id, false, 0)).first()
        val second = db.noteDao().diaryRows(PrivateDiary.rows(book.id, false, 1)).first()
        assertEquals(201, first.size); assertEquals(5, second.size)
        assertEquals(205, (first.take(200) + second).map { it.id }.toSet().size)
        assertTrue((first + second).all { it.preview.length == 160 })
    }

    @Test fun `sync always supplies private parent metadata and rejects a private note without its parent`() = runBlocking {
        val book = book(); db.notebookDao().update(book.copy(dirty = false))
        val note = insert(NoteEntity(notebookId = book.id, title = "Private", updatedAt = 100, dirty = true))
        val source = RoomSyncDataSource(app, db); val delta = source.getChangesSince(50)
        assertTrue(delta.notebooks.single().isDiary == true); assertTrue(delta.notes.single().diary == true)
        assertEquals(book.syncId, delta.notes.single().notebookSyncId)
        val orphan = NoteDto("orphan-private", "SECRET", "must not be public", false, false, 1, 2, notebookSyncId = "absent", diary = true)
        assertTrue(runCatching { source.applyChanges(ChangeSet(notes = listOf(orphan))) }.isFailure)
        assertNull(db.noteDao().getBySyncId(orphan.syncId)); assertEquals(note, db.noteDao().getById(note.id))
    }

    @Test fun `legacy peer cannot remove private marker credentials or parent membership`() = runBlocking {
        val book = book(); val note = insert(NoteEntity(notebookId = book.id, title = "Before", updatedAt = 1, dirty = false))
        val source = RoomSyncDataSource(app, db)
        source.applyChanges(ChangeSet(notebooks = listOf(NotebookDto(book.syncId, book.name, true, 10)), notes = listOf(
            NoteDto(note.syncId, "Remote edit", "Preserved private", false, false, note.createdAt, 11))))
        val afterBook = db.notebookDao().getById(book.id)!!; val after = db.noteDao().getById(note.id)!!
        assertTrue(afterBook.isDiary); assertTrue(afterBook.isProtected); assertFalse(afterBook.isDeleted)
        assertEquals(book.passwordHash, afterBook.passwordHash); assertEquals(book.passwordSalt, afterBook.passwordSalt); assertTrue(afterBook.dirty)
        assertEquals(book.id, after.notebookId); assertEquals("Preserved private", after.content)
        ProtectionSession.unlockNotebook(book.id)
        assertTrue(db.noteDao().rawQuery(NotesViewModel.buildQuery("", NoteFilter.All, NoteSort.BY_DATE, setOf(book.id))).first().isEmpty())
    }

    @Test fun `invalid private notebook metadata rejects the package before ordinary mutations`() = runBlocking {
        val invalid = NotebookDto("invalid-private", "Diary", false, 10, isDiary = true)
        val source = RoomSyncDataSource(app, db)
        assertTrue(runCatching { source.applyChanges(ChangeSet(notebooks = listOf(NotebookDto("ordinary", "Keep absent", false, 1), invalid))) }.isFailure)
        assertNull(db.notebookDao().getBySyncId("ordinary")); assertNull(db.notebookDao().getBySyncId(invalid.syncId))
    }

    @Test fun `background epoch prevents late password grant and an explicit foreground retry works`() = runBlocking {
        val book = book(); ProtectionSession.lockNotebook(book.id)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val vault = object : NotebookBiometricVault {
            override val bindings = MutableStateFlow<Map<String,String>>(emptyMap())
            override suspend fun load() {}
            override suspend fun prepare(binding: NotebookBiometricBinding, enroll: Boolean): BiometricChallenge = error("Not used")
            override suspend fun verify(challenge: BiometricChallenge, cipher: Cipher) = false
            override suspend fun discard(challenge: BiometricChallenge) {}
            override suspend fun revoke(syncId: String) {}
        }
        val controller = NotebookBiometricController(scope, db, vault)
        try {
            val late = CompletableDeferred<Boolean>()
            controller.unlockPassword(book, "test-only-diary-password") { late.complete(it) }
            DiarySession.lock()
            assertFalse(withTimeout(10_000) { late.await() }); assertFalse(ProtectionSession.isUnlocked(book))
            val retry = CompletableDeferred<Boolean>()
            controller.unlockPassword(book, "test-only-diary-password") { retry.complete(it) }
            assertTrue(withTimeout(10_000) { retry.await() }); assertTrue(ProtectionSession.isUnlocked(book))
        } finally { controller.cancel(); scope.cancel() }
    }

    @Test fun `stale open editor preserves newly private membership and clears presentation when the session locks`() = runBlocking {
        val book = book(); val old = insert(NoteEntity(title = "Legacy", content = "Original body", dayKey = 1))
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to old.id))).also { models.put("editor", it) }
        await { vm.isReady }
        assertEquals(1, PrivateDiary.move(db, book, listOf(NoteIdentity(old.id, old.syncId))))
        vm.assignNotebook(null)
        assertTrue(vm.saveNow(force = true)); assertEquals(book.id, db.noteDao().getById(old.id)!!.notebookId)
        DiarySession.lock(); await { vm.accessDenied }
        assertEquals("", vm.titleField.text); assertEquals("", vm.contentField.text)
        assertEquals("Original body", db.noteDao().getById(old.id)!!.content)
    }

    @Test fun `encrypted backup roundtrip preserves private identity and invalid staging cannot replace live data`() = runBlocking {
        val book = book(); val note = DiaryEntries.create(db, book)
        val original = note.copy(content = "Private backup text"); db.noteDao().update(original)
        val output = ByteArrayOutputStream(); EncryptedBackupFixture.createBackup(app, output)
        val good = File(app.cacheDir, "private-diary-backup.nls").apply { writeBytes(output.toByteArray()) }
        db.noteDao().update(original.copy(content = "Changed"))
        val restored = EncryptedBackupFixture.restore(app, Uri.fromFile(good)); assertTrue(restored.exceptionOrNull()?.stackTraceToString(), restored.isSuccess)
        assertEquals(original, db.noteDao().getById(note.id)); assertEquals(book, db.notebookDao().getById(book.id)); assertFalse(ProtectionSession.isUnlocked(book))
        db.notebookDao().update(book.copy(isProtected = false))
        val malformed = ByteArrayOutputStream(); EncryptedBackupFixture.createBackup(app, malformed)
        val bad = File(app.cacheDir, "invalid-private-diary.nls").apply { writeBytes(malformed.toByteArray()) }
        db.notebookDao().update(book)
        val live = insert(NoteEntity(title = "Keep live", content = "Current data"))
        val result = EncryptedBackupFixture.restore(app, Uri.fromFile(bad)); assertTrue(result.isFailure)
        assertEquals(live, db.noteDao().getById(live.id)); assertEquals(book, db.notebookDao().getById(book.id)); assertEquals(original, db.noteDao().getById(note.id))
    }
}
