package com.notessync.app.notes.diary

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.backup.EncryptedBackupFixture
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.*
import com.notessync.app.data.local.model.NoteIdentity
import com.notessync.app.notes.actions.NoteActions
import com.notessync.app.notes.text.TextFormatting
import com.notessync.app.notes.text.FormatMark
import com.notessync.app.notes.text.FormatKind
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
            isCompleted = true, isFavorite = true, isProtected = true, taskDueAt = 1, dayKey = 20_000))
        tag(note); ProtectionSession.unlockTask(note.syncId)
        for (filter in listOf(NoteFilter.All, NoteFilter.Tasks, NoteFilter.Completed, NoteFilter.Unfinished,
            NoteFilter.Overdue, NoteFilter.Favorites, NoteFilter.Notebook(book.id, book.name), NoteFilter.Tag(DiaryEntries.TAG_NAME))) {
            val query = NotesViewModel.buildQuery("", filter, NoteSort.BY_DATE, setOf(book.id), setOf(note.syncId))
            assertTrue(db.noteDao().rawQuery(query).first().isEmpty())
            assertTrue(db.noteDao().rawCards(NotesViewModel.buildCardQuery("", filter, NoteSort.BY_DATE, setOf(book.id), setOf(note.syncId))).first().isEmpty())
        }
        assertTrue(visibleNotes(listOf(note), listOf(book), ProtectionSession.state.value).isEmpty())
        val card = db.noteDao().diaryRows(PrivateDiary.rows(book.id, false, 0)).first().single()
        assertEquals(note.id, card.id); assertEquals("", card.title); assertEquals("", card.preview)
        assertNull(card.dayKey); assertEquals(0L, card.updatedAt)
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

    @Test fun `session lock epoch prevents late password grant and an explicit foreground retry works`() = runBlocking {
        val book = book(); ProtectionSession.lockNotebook(book.id)
        val queued = StandardTestDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queued)
        suspend fun result(done: CompletableDeferred<Boolean>): Boolean = withTimeout(10_000) {
            while (!done.isCompleted) { queued.scheduler.runCurrent(); delay(5) }
            done.await()
        }
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
            assertFalse(result(late)); assertFalse(ProtectionSession.isUnlocked(book))
            val retry = CompletableDeferred<Boolean>()
            controller.unlockPassword(book, "test-only-diary-password") { retry.complete(it) }
            assertTrue(result(retry)); assertTrue(ProtectionSession.isUnlocked(book))
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

    @Test fun `private editor can trash its authorized entry while a general action and a locked editor cannot`() = runBlocking {
        val book = book()
        val formatting = TextFormatting(marks = listOf(FormatMark(0, 4, FormatKind.BOLD.name, "1"))).encode()
        val note = insert(NoteEntity(title = "Private editor", content = "Keep journal text", textFormat = formatting, notebookId = book.id))
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id))).also { models.put("delete-editor", it) }
        await { vm.isReady }
        assertEquals(0, NoteActions(app, db).apply(setOf(note.id), NoteActions.Action.TRASH, mapOf(note.id to note.syncId)))
        assertEquals(note.id, vm.deleteNow())
        val trashed = db.noteDao().getById(note.id)!!
        assertTrue(trashed.isDeleted); assertEquals(book.id, trashed.notebookId)
        assertEquals(note.syncId, trashed.syncId); assertEquals(note.content, trashed.content); assertEquals(note.textFormat, trashed.textFormat)
        assertEquals(1, NoteActions(app, db).apply(setOf(note.id), NoteActions.Action.RESTORE, mapOf(note.id to note.syncId), book.id))
        val locked = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id))).also { models.put("locked-delete-editor", it) }
        await { locked.isReady }
        DiarySession.lock(); await { locked.accessDenied }
        assertEquals(-1L, locked.deleteNow()); assertFalse(db.noteDao().getById(note.id)!!.isDeleted)
        assertEquals(note.content, db.noteDao().getById(note.id)!!.content)
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
    @Test fun `legacy private tasks and trash move without losing either password while ordinary dated tasks require explicit selection`() = runBlocking {
        val diary = book()
        val parent = NotebookEntity(name = "Protected source", isProtected = true).let { it.copy(id = db.notebookDao().upsert(it)) }
        val own = PasswordProtector.create("separate-entry-password")
        val protectedEntry = insert(NoteEntity(title = "Protected legacy", notebookId = parent.id, isTask = true, isProtected = true,
            passwordHash = own.hash, passwordSalt = own.salt, dayKey = 1))
        val trash = insert(NoteEntity(title = "Old journal trash", isDeleted = true, dayKey = 1))
        val dated = insert(NoteEntity(title = "Dated task", isTask = true, dayKey = 1, taskPriority = 2,
            taskElapsedMillis = 123, taskCountdownMillis = 987, taskDueAt = 123456))
        tag(protectedEntry); tag(trash)
        val before = db.noteDao().diaryCandidates(PrivateDiary.candidates(emptySet(), 0)).first()
        assertFalse(before.any { it.id == protectedEntry.id }); assertTrue(before.single { it.id == trash.id }.isDeleted)
        ProtectionSession.unlockNotebook(parent.id); ProtectionSession.unlockTask(protectedEntry.syncId)
        val preview = db.noteDao().diaryCandidates(PrivateDiary.candidates(setOf(parent.id), 0, setOf(protectedEntry.syncId))).first()
        assertTrue(preview.any { it.id == protectedEntry.id }); assertFalse(preview.any { it.id == dated.id })
        assertEquals(2, PrivateDiary.move(db, diary, listOf(protectedEntry, trash, dated).map { NoteIdentity(it.id, it.syncId) }))
        val privateAfter = db.noteDao().getById(protectedEntry.id)!!; val trashAfter = db.noteDao().getById(trash.id)!!
        assertEquals(protectedEntry.copy(notebookId = diary.id, updatedAt = privateAfter.updatedAt, dirty = true), privateAfter)
        assertEquals(trash.copy(notebookId = diary.id, updatedAt = trashAfter.updatedAt, dirty = true), trashAfter)
        assertEquals(dated, db.noteDao().getById(dated.id))
        assertEquals(1, PrivateDiary.move(db, diary, listOf(NoteIdentity(dated.id, dated.syncId)), includeTasks = true))
        val taskAfter = db.noteDao().getById(dated.id)!!
        assertEquals(dated.copy(notebookId = diary.id, updatedAt = taskAfter.updatedAt, dirty = true), taskAfter)
        assertTrue(db.noteDao().diaryRows(PrivateDiary.rows(diary.id, true, 0)).first().any { it.id == trash.id })
    }

    @Test fun `password changes verify fresh credentials relock and cannot be undone by a legacy peer rename`() = runBlocking {
        val diary = book(); val entry = DiaryEntries.create(db, diary)
        assertFalse(PrivateDiary.changePassword(db, diary, "wrong", "replacement-password"))
        assertFalse(PrivateDiary.changePassword(db, diary, "test-only-diary-password", "replacement-password") { false })
        assertEquals(diary, db.notebookDao().getById(diary.id)); assertTrue(ProtectionSession.isUnlocked(diary))
        assertTrue(PrivateDiary.changePassword(db, diary, "test-only-diary-password", "replacement-password"))
        val changed = db.notebookDao().getById(diary.id)!!
        assertFalse(ProtectionSession.isUnlocked(changed)); assertTrue(changed.updatedAt > diary.updatedAt)
        assertTrue(PasswordProtector.verify("replacement-password", PasswordRecord(changed.passwordHash!!, changed.passwordSalt!!)))
        assertFalse(PasswordProtector.verify("test-only-diary-password", PasswordRecord(changed.passwordHash!!, changed.passwordSalt!!)))
        assertEquals(entry, db.noteDao().getById(entry.id))
        val legacy = NotebookDto(diary.syncId, "Old peer name", false, changed.updatedAt + 1, true, diary.passwordHash, diary.passwordSalt)
        RoomSyncDataSource(app, db).applyChanges(ChangeSet(notebooks = listOf(legacy)))
        val after = db.notebookDao().getById(diary.id)!!
        assertEquals(changed.passwordHash, after.passwordHash); assertEquals(changed.passwordSalt, after.passwordSalt)
        assertTrue(after.isDiary); assertTrue(after.isProtected)
    }

    @Test fun `generic notebook controls cannot rename unprotect delete or detach a private diary`() = runBlocking {
        val diary = book(); val note = DiaryEntries.create(db, diary)
        val vm = NotesViewModel(app).also { models.put("home", it) }
        suspend fun action(block: () -> Unit) {
            val parent = vm.viewModelScope.coroutineContext[Job]!!
            val old = parent.children.toSet(); block()
            withTimeout(15_000) { parent.children.filter { it !in old }.toList().joinAll() }
        }
        action { vm.renameNotebook(diary, "Generic rename") }
        action { vm.deleteNotebook(diary) }
        val removed = CompletableDeferred<Boolean>()
        vm.removeNotebookProtection(diary, "test-only-diary-password") { removed.complete(it) }
        assertFalse(withTimeout(15_000) { removed.await() })
        assertEquals(diary, db.notebookDao().getById(diary.id)); assertEquals(note, db.noteDao().getById(note.id))
    }

    @Test fun `staging validation preserves cancellation and never changes live private data`() = runBlocking {
        val diary = book(); val note = DiaryEntries.create(db, diary)
        val error = runCatching { validateDiarySnapshot(db.openHelper.readableDatabase) { throw CancellationException("test-only-cancel") } }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(diary, db.notebookDao().getById(diary.id)); assertEquals(note, db.noteDao().getById(note.id))
    }

}
