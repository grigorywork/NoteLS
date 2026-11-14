package com.notessync.app.audit

import android.app.AlarmManager
import android.app.Application
import android.os.Looper
import android.net.Uri
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.backup.crypto.*
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.notes.tables.*
import com.notessync.app.notes.text.*
import com.notessync.app.reminders.*
import com.notessync.app.security.*
import com.notessync.app.settings.*
import com.notessync.app.sync.android.RoomSyncDataSource
import com.notessync.app.ui.notes.*
import com.notessync.app.ui.tasks.TaskCompletion
import com.notessync.app.widget.DayTaskActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.util.Random
import java.util.UUID

/** Stateful randomized validation of the frozen cloud artifact, never a phone/CPU/battery benchmark. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CloudRoomLongAudit {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val manager get() = app.getSystemService(AlarmManager::class.java)
    private val random = Random((System.getProperty("notels.audit.seed") ?: "202611143").toLong())
    private val counts = LongArray(11)
    private var iterations = 0L
    private val password get() = "PUBLIC SYNTHETIC AUDIT PASSWORD".toCharArray()
    private val runtime = Class.forName("java.lang.management.ManagementFactory").getMethod("getRuntimeMXBean").invoke(null)
    private val uptimeMethod = Class.forName("java.lang.management.RuntimeMXBean").getMethod("getUptime")
    private fun uptime(): Long = uptimeMethod.invoke(runtime) as Long
    private var activeMillis = 0L
    private var excludedMillis = 0L
    private var operation = "startup"
    private lateinit var store: ViewModelStore
    private val editors = mutableListOf<NoteEditorViewModel>()

    @Before fun before() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); ProtectionSession.lockAll()
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        app.createReminderChannel()
        BackupProtection(app).save(BackupIdentity.create(password, 100000))
    }
    @After fun after() {
        AppDatabase.closeInstance(); ProtectionSession.lockAll(); Dispatchers.resetMain()
    }
    private fun text() = "fixture $iterations " + "аб😀xy".repeat(1 + random.nextInt(600))
    private suspend fun save(note: NoteEntity) = note.copy(id = db.noteDao().upsert(note))
    private suspend fun open(note: NoteEntity): NoteEditorViewModel {
        operation += "/open"
        val vm = NoteEditorViewModel(app, SavedStateHandle(mapOf("noteId" to note.id, "isTask" to note.isTask)))
        editors.add(vm); store.put("editor-${editors.size}", vm)
        operation += "/ready"
        withContext(Dispatchers.IO) { withTimeout(15000) { while (!vm.isReady && !vm.accessDenied) delay(2) } }
        operation += "/loaded"
        return vm
    }
    private fun home() = NotesViewModel(app).also { store.put("home", it) }
    private suspend fun homeCommand(vm: NotesViewModel, action: () -> Unit) {
        val job = vm.viewModelScope.coroutineContext[Job]!!; val previous = job.children.toSet()
        action(); withTimeout(15000) { job.children.filter { it !in previous }.toList().joinAll() }
    }
    private suspend fun unlock(vm: NotesViewModel, note: NoteEntity, value: String): Boolean {
        val done = CompletableDeferred<Boolean>(); vm.unlockTask(note, value) { done.complete(it) }
        return withTimeout(15000) { done.await() }
    }
    private suspend fun freshProtection() {
        val original = save(NoteEntity(id = 1, title = "Public", content = text(), isTask = true))
        val editor = open(original); assertTrue(editor.isReady)
        val value = "fresh-fixture-$iterations"; val record = PasswordProtector.create(value)
        db.noteDao().update(original.copy(isProtected = true, passwordHash = record.hash, passwordSalt = record.salt, updatedAt = original.updatedAt + 1))
        assertTrue(unlock(home(), original, value))
        val changed = text(); editor.onContentChange(TextFieldValue(changed)); assertTrue(editor.saveNow(force = true))
        val current = db.noteDao().getById(1)!!
        assertEquals(changed, current.content); assertTrue(current.isProtected); assertEquals(record.hash, current.passwordHash)
    }
    private suspend fun stalePassword() {
        val old = PasswordProtector.create("old-fixture-$iterations")
        val note = save(NoteEntity(id = 1, title = "Private", isTask = true, isProtected = true,
            passwordHash = old.hash, passwordSalt = old.salt))
        val changed = PasswordProtector.create("new-fixture-$iterations")
        val purged = random.nextBoolean()
        db.noteDao().update(note.copy(passwordHash = changed.hash, passwordSalt = changed.salt, isPurged = purged, isDeleted = purged))
        assertFalse(unlock(home(), note, "old-fixture-$iterations"))
        assertFalse(ProtectionSession.isUnlocked(note))
    }
    private suspend fun conversion() {
        val body = text(); val record = PasswordProtector.create("owner-fixture-$iterations")
        val table = NoteTables().add("audit", 2, 2).editCell(TableCellRef("audit", 0, 1), notePreviewText(text(), 200)).encode()
        val format = TextFormatting().apply(0, body.length, FormatKind.BOLD, "1").encode()
        val note = save(NoteEntity(id = 1, title = "Protected task", content = body, tablesData = table, textFormat = format,
            isTask = true, isProtected = true, passwordHash = record.hash, passwordSalt = record.salt, isPinned = true,
            taskDueAt = System.currentTimeMillis() + 3600000))
        ProtectionSession.unlockTask(note.syncId)
        val editor = open(note); assertTrue(editor.isReady); editor.toggleTask(); assertTrue(editor.saveNow(force = true))
        val current = db.noteDao().getById(1)!!
        assertFalse(current.isTask); assertFalse(current.isProtected); assertNull(current.taskDueAt)
        assertEquals(body, current.content); assertEquals(table, current.tablesData); assertEquals(format, current.textFormat)
        assertTrue(current.isPinned); assertFalse(note.syncId in ProtectionSession.state.value.taskSyncIds)
    }
    private suspend fun peerTargets() {
        val now = System.currentTimeMillis(); val first = now + 3600000 + random.nextInt(10000)
        val note = save(NoteEntity(id = 1, title = "Peer", isTask = true, reminderAt = first, dirty = false))
        val other = save(NoteEntity(id = 2, title = "Control", reminderAt = first + 1000, dirty = false))
        assertEquals(AlarmCommandResult.APPLIED, RoomNoteAlarmCommands(app).schedule(other.id, other.syncId, other.reminderAt!!, false) { true })
        val source = RoomSyncDataSource(app, db); val before = source.getChangesSince(Long.MIN_VALUE)
        val dto = before.notes.single { it.syncId == note.syncId }
        val next = first + 3600000
        val clear = random.nextBoolean()
        source.applyChanges(before.copy(notes = listOf(dto.copy(updatedAt = dto.updatedAt + 1,
            reminderAt = if (clear) null else next, taskCountdownMillis = if (clear) 0 else 3600000,
            taskCountdownEndAt = if (clear) null else next + 1000))))
        verifyCurrentAlarms(if (clear) 1 else 3)
    }
    private suspend fun verifyCurrentAlarms(expected: Int) {
        val alarms = shadowOf(manager).scheduledAlarms
        assertEquals(expected, alarms.size)
        for (alarm in alarms) {
            val intent = shadowOf(alarm.operation).savedIntent
            val id = intent.getLongExtra(ReminderReceiver.EXTRA_NOTE_ID, -1)
            val row = db.noteDao().getAlarmSnapshot(id)!!
            val timer = intent.getBooleanExtra(ReminderReceiver.EXTRA_IS_TIMER, false)
            val target = if (timer) row.taskCountdownEndAt else row.reminderAt
            assertFalse(row.isDeleted); assertFalse(row.isPurged)
            assertEquals(row.syncId, intent.getStringExtra(ReminderReceiver.EXTRA_SYNC_ID))
            assertEquals(target, intent.getLongExtra(ReminderReceiver.EXTRA_EXPECTED_AT, -1))
        }
    }
    private class Gate(private val delegate: NoteAlarmCommands, private val before: suspend () -> Unit) : NoteAlarmCommands {
        override suspend fun schedule(id: Long, syncId: String, at: Long, timer: Boolean, current: () -> Boolean): AlarmCommandResult {
            before(); return delegate.schedule(id, syncId, at, timer, current)
        }
        override suspend fun cancel(id: Long, syncId: String, timer: Boolean, current: () -> Boolean) = delegate.cancel(id, syncId, timer, current)
    }
    private suspend fun acceptedAlarm() {
        val note = save(NoteEntity(id = 1, title = "Fixture", isTask = true))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val commands = RoomNoteAlarmCommands(app)
        lateinit var timing: EditorTaskTiming
        timing = EditorTaskTiming(app, scope, { true }, { false }, { false }, { note.id },
            persist = {
                val fresh = db.noteDao().getById(note.id)!!
                db.noteDao().update(fresh.copy(reminderAt = timing.reminderAt, taskCountdownEndAt = timing.taskCountdownEndAt)); true
            }, report = {}, parentSyncId = { note.syncId }, alarms = Gate(commands) { entered.complete(Unit); release.await() })
        try {
            val first = System.currentTimeMillis() + 3600000
            val timer = random.nextBoolean()
            if (timer) assertTrue(timing.startCountdownUntil(first)) else timing.setReminder(first)
            withTimeout(15000) { entered.await() }
            val variant = random.nextInt(4)
            when (variant) {
                0 -> scope.cancel()
                1, 2 -> {
                    val current = db.noteDao().getById(1)!!; val next = first + 3600000
                    val changed = current.copy(syncId = if (variant == 1) UUID.randomUUID().toString() else current.syncId,
                        reminderAt = if (timer) null else next, taskCountdownEndAt = if (timer) next else null)
                    db.noteDao().update(changed)
                    assertEquals(AlarmCommandResult.APPLIED, commands.schedule(changed.id, changed.syncId, next, timer) { true })
                }
                else -> { if (timer) timing.cancelCountdown() else timing.clearReminder() }
            }
            release.complete(Unit); withTimeout(15000) { timing.awaitIdle() }
            verifyCurrentAlarms(if (variant == 3) 0 else 1)
            if (variant == 0) {
                val previous = db.noteDao().getById(1)!!
                timing.setReminder(first + 7200000)
                assertEquals(previous, db.noteDao().getById(1))
            }
        } finally { release.complete(Unit); scope.cancel(); timing.awaitIdle() }
    }
    private suspend fun archiveRestore() {
        val body = text(); val original = save(NoteEntity(id = 1, title = "Roundtrip", content = body, isPinned = true))
        val file = File(app.cacheDir, "cloud-audit-copy.nlsbackup")
        try {
            file.outputStream().use { BackupManager.createBackup(app, it) }
            db.noteDao().update(original.copy(content = "Changed after archive"))
            BackupManager.unlock(app, Uri.fromFile(file), password).use { access ->
                assertTrue(BackupManager.restore(app, Uri.fromFile(file), access).isSuccess)
            }
            assertEquals(body, db.noteDao().getById(1)!!.content); assertTrue(db.noteDao().getById(1)!!.isPinned)
        } finally { file.delete() }
    }
    private suspend fun staleCard() {
        val note = save(NoteEntity(id = 1, title = "Original", isTask = true, dirty = false))
        val card = db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.All, NoteSort.BY_DATE)).first().single()
        val changed = note.copy(syncId = UUID.randomUUID().toString(), title = "Restored")
        db.noteDao().update(changed)
        val vm = home(); assertEquals(0, vm.delete(card))
        homeCommand(vm) { vm.toggleCompleted(card) }
        assertFalse(db.noteDao().getById(1)!!.isCompleted); assertFalse(db.noteDao().getById(1)!!.isDeleted)
        val next = db.noteDao().rawCards(NotesViewModel.buildCardQuery("", NoteFilter.All, NoteSort.BY_DATE)).first().single()
        db.noteDao().update(changed.copy(isCompleted = true))
        homeCommand(vm) { vm.toggleCompleted(next) }; assertTrue(db.noteDao().getById(1)!!.isCompleted)
    }
    private suspend fun editorCommand(vm: NoteEditorViewModel, action: () -> Unit) {
        val job = vm.viewModelScope.coroutineContext[Job]!!; val previous = job.children.toSet()
        action(); withTimeout(15000) { job.children.filter { it !in previous }.toList().joinAll() }
    }
    private suspend fun tags() {
        operation = "tags/fixture"
        val note = save(NoteEntity(id = 1, title = "Owner", content = text(), dirty = false))
        val editor = open(note); val replacement = random.nextBoolean()
        if (replacement) db.noteDao().update(note.copy(syncId = UUID.randomUUID().toString()))
        val name = "fixture-tag-${random.nextInt(10000)}"
        operation = "tags/add"
        editorCommand(editor) { editor.addTag(name) }
        operation = "tags/check-add"
        if (replacement) assertTrue(db.tagDao().getTagNamesForNote(1).isEmpty())
        else {
            val tagged = db.noteDao().getById(1)!!
            assertTrue(tagged.dirty); assertTrue(tagged.updatedAt > note.updatedAt)
            assertEquals(listOf(name), db.tagDao().getTagNamesForNote(1)); assertEquals(note.content, tagged.content)
            operation = "tags/remove"
            editorCommand(editor) { editor.removeTag(name) }
            operation = "tags/check-remove"
            assertTrue(db.tagDao().getTagNamesForNote(1).isEmpty())
            assertTrue(db.noteDao().getById(1)!!.updatedAt > tagged.updatedAt)
        }
    }
    private suspend fun versions() {
        val now = System.currentTimeMillis(); val previous = now + 3600000 + random.nextInt(3600000)
        val note = save(NoteEntity(id = 1, title = "Skew", content = text(), isTask = true,
            taskTimerStartedAt = now - 1000, updatedAt = previous, dirty = false))
        when (random.nextInt(3)) {
            0 -> {
                val editor = open(note); val body = "changed-" + text(); editor.onContentChange(TextFieldValue(body))
                assertTrue(editor.saveNow(force = true)); assertEquals(body, db.noteDao().getById(1)!!.content)
            }
            1 -> {
                db.noteDao().markContentChanged(1, previous)
                db.noteDao().markVersionSynced(note.syncId, previous)
                assertTrue(db.noteDao().getById(1)!!.dirty)
            }
            else -> {
                val completed = TaskCompletion.toggle(note, now)
                assertEquals(1000L, completed.taskElapsedMillis); assertEquals(now, completed.taskCompletedAt)
                db.noteDao().update(completed)
            }
        }
        assertTrue(db.noteDao().getById(1)!!.updatedAt > previous)
    }
    private suspend fun dayReopen() {
        val now = System.currentTimeMillis(); val target = now + 3600000 + random.nextInt(10000)
        val note = save(NoteEntity(id = 1, title = "Reopened", dayKey = 20000, isTask = true,
            isCompleted = true, reminderAt = target, taskCountdownMillis = 7200000, taskCountdownEndAt = target + 3600000))
        assertTrue(DayTaskActions.setCompleted(app, 1, 20000, false, expectedSyncId = note.syncId))
        verifyCurrentAlarms(2)
        assertTrue(DayTaskActions.setCompleted(app, 1, 20000, true, expectedSyncId = note.syncId))
        verifyCurrentAlarms(0)
    }
    private suspend fun blankClock() {
        val editor = NoteEditorViewModel(app, SavedStateHandle(mapOf("isTask" to true)))
        editors.add(editor); store.put("editor-${editors.size}", editor)
        withContext(Dispatchers.IO) { withTimeout(15000) { while (!editor.isReady) delay(2) } }
        val timer = random.nextBoolean()
        if (timer) assertTrue(editor.startCountdownUntil(System.currentTimeMillis() + 3600000)) else editor.toggleTaskTimer()
        assertTrue(editor.flushOnExit())
        val saved = db.noteDao().getById(editor.currentNoteId)!!
        assertFalse(saved.isDeleted)
        if (timer) assertNotNull(saved.taskCountdownEndAt) else assertNotNull(saved.taskTimerStartedAt)
    }
    private fun report(status: String) {
        println("CLOUD_ROOM_AUDIT status=$status utc=${Instant.now()} seed=${(System.getProperty("notels.audit.seed") ?: "202611143")} iterations=$iterations cases=${counts.joinToString(",")} activeMillis=$activeMillis excludedMillis=$excludedMillis operation=$operation")
        System.out.flush()
    }
    @Test fun sustainedStatefulAudit() = runBlocking {
        val target = (System.getProperty("notels.audit.active.seconds") ?: "20").toLong() * 1000
        val stop = File((System.getProperty("notels.audit.stop.file") ?: "/workspace/tools/notels-native-audit/STOP"))
        var last = uptime(); var printed = last
        report("started")
        try {
            do {
                // An actual Android main loop runs between interactions; the long standalone harness must drive it too.
                shadowOf(Looper.getMainLooper()).idle()
                store = ViewModelStore(); editors.clear(); ProtectionSession.lockAll()
                val choice = random.nextInt(counts.size); operation = choice.toString()
                try {
                    when (choice) {
                        0 -> freshProtection(); 1 -> stalePassword(); 2 -> conversion(); 3 -> peerTargets()
                        4 -> acceptedAlarm(); 5 -> archiveRestore(); 6 -> staleCard(); 7 -> tags()
                        8 -> versions(); 9 -> dayReopen(); else -> blankClock()
                    }
                    counts[choice]++; iterations++
                } finally {
                    for (editor in editors) editor.flushOnExit()
                    store.clear()
                    shadowOf(manager).scheduledAlarms.toList().forEach { it.operation?.let { pending -> manager.cancel(pending); pending.cancel() } }
                    withContext(Dispatchers.IO) { db.clearAllTables() }
                    ProtectionSession.lockAll()
                    shadowOf(Looper.getMainLooper()).idle()
                }
                val now = uptime(); val delta = now - last; last = now
                if (delta > 30000) { excludedMillis += delta; report("execution_gap_excluded") } else activeMillis += delta
                if (now - printed >= 15000) { report("running"); printed = now }
            } while (activeMillis < target && !stop.isFile)
            report(if (stop.isFile) "stopped" else "completed")
        } catch (error: Throwable) {
            report("FAILED")
            Thread.getAllStackTraces().entries.sortedBy { it.key.name }.forEach { (thread, frames) ->
                System.err.println("AUDIT_THREAD name=${thread.name} state=${thread.state}\n" + frames.take(24).joinToString("\n"))
            }
            throw error
        }
    }
}
