package com.notessync.app.data.backup

import com.notessync.app.i18n.uiText

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.widget.CalendarWidgetProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Finite, event-driven work only: no service, repeating job, wake lock or polling timer. */
object BackupManager {
    data class BackupInfo(val createdAt: Long, val noteCount: Long, val dbVersion: Int)
    data class LocalBackup(val file: File, val info: BackupInfo)
    private val operation = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revision = AtomicLong(0)
    private var backedUpRevision = 0L
    private val tables = listOf("notebooks", "tags", "notes", "note_tag_cross_ref", "attachments")

    fun noteChanged() { revision.incrementAndGet() }

    fun requestAutoBackup(context: Context, afterNote: Boolean) {
        val app = context.applicationContext
        scope.launch { autoBackupIfDue(app, afterNote) }
    }

    suspend fun createBackup(context: Context, out: OutputStream): BackupInfo = withContext(Dispatchers.IO) {
        operation.withLock { createUnlocked(context, out) }
    }

    private fun createUnlocked(context: Context, out: OutputStream): BackupInfo {
        val temp = temporaryDirectory(context, "snapshot-")
        try {
            val room = AppDatabase.get(context)
            val snapshot = File(temp, BackupArchive.DATABASE)
            // Hold the writer lock while copying DB + WAL; checkpoint only the isolated copy.
            room.runInTransaction {
                val live = context.getDatabasePath(BackupArchive.DATABASE)
                live.copyTo(snapshot)
                File(live.path + "-wal").takeIf { it.isFile }?.copyTo(File(snapshot.path + "-wal"))
            }
            val info: BackupInfo
            val photos = linkedMapOf<String, File>()
            SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0) { uiText("База занята, повторите копирование") }
                }
                val count = db.rawQuery("SELECT COUNT(*) FROM notes WHERE isDeleted = 0", null).use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                info = BackupInfo(System.currentTimeMillis(), count, db.version)
                val root = File(context.filesDir, "attachments").canonicalFile
                db.rawQuery("SELECT filePath FROM attachments", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val photo = File(cursor.getString(0)).canonicalFile
                        check(photo.path.startsWith(root.path + File.separator) && photo.isFile) {
                            uiText("Не найдена фотография: %1\$s", (photo.name))
                        }
                        photos[photo.relativeTo(root).invariantSeparatorsPath] = photo
                    }
                }
            }
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
                zip.write(BackupArchive.manifest(info.archiveInfo()).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.addFile(BackupArchive.DATABASE, snapshot)
                photos.forEach { (path, file) -> zip.addFile(BackupArchive.ATTACHMENTS + path, file) }
            }
            return info
        } finally { temp.deleteRecursively() }
    }

    suspend fun inspect(context: Context, uri: Uri): BackupInfo? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use {
                BackupArchive.inspect(it).also { info -> check(info.dbVersion <= AppDatabase.VERSION) }.publicInfo()
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    suspend fun localBackups(context: Context): List<LocalBackup> = withContext(Dispatchers.IO) {
        autoDirectories(context).flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isFile && it.name.startsWith("notels-auto-") && it.extension == "zip" }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file -> inspect(context, Uri.fromFile(file))?.let { LocalBackup(file, it) } }
            .take(5)
    }

    suspend fun restore(context: Context, uri: Uri): Result<BackupInfo> = withContext(Dispatchers.IO) {
        operation.withLock {
            val temp = File(context.cacheDir, "restore-${UUID.randomUUID()}")
            val incoming = File(context.filesDir, "attachments/restored-${UUID.randomUUID()}")
            var committed = false
            try {
                check(temp.mkdirs()) { uiText("Не удалось подготовить восстановление") }
                val info = context.contentResolver.openInputStream(uri)?.use { BackupArchive.extract(it, temp) }
                    ?: error(uiText("Не удалось открыть файл"))
                check(info.dbVersion <= AppDatabase.VERSION) { uiText("Обновите NoteLS для восстановления этой копии") }
                val snapshot = File(temp, BackupArchive.DATABASE)
                SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    check(db.version == info.dbVersion) { uiText("Версия базы не совпадает с манифестом") }
                    db.rawQuery("PRAGMA quick_check", null).use {
                        check(it.moveToFirst() && it.getString(0) == "ok") { uiText("База резервной копии повреждена") }
                    }
                }
                // Room migrates and validates the staged schema before the live DB is touched.
                val source = AppDatabase.openForRestore(context, snapshot.absolutePath)
                try {
                    val packed = source.openHelper.writableDatabase
                    packed.query("PRAGMA foreign_key_check").use {
                        check(!it.moveToFirst()) { uiText("Нарушены связи записей резервной копии") }
                    }
                    val count = packed.query("SELECT COUNT(*) FROM notes WHERE isDeleted = 0").use {
                        check(it.moveToFirst()); it.getLong(0)
                    }
                    check(count == info.noteCount) { uiText("Число заметок не совпадает с манифестом") }
                    check(incoming.mkdirs()) { uiText("Не удалось подготовить фотографии") }
                    packed.query("SELECT id, filePath FROM attachments").use { cursor ->
                        while (cursor.moveToNext()) {
                            val relative = BackupArchive.relativeAttachmentPath(cursor.getString(1))
                            val photo = File(temp, BackupArchive.ATTACHMENTS + relative)
                            check(photo.isFile) { uiText("В копии отсутствует фотография") }
                            val stableName = UUID.nameUUIDFromBytes(relative.toByteArray(Charsets.UTF_8)).toString()
                            val target = File(incoming, stableName)
                            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                            if (!target.isFile) photo.copyTo(target)
                            packed.execSQL("UPDATE attachments SET filePath = ? WHERE id = ?",
                                arrayOf(target.absolutePath, cursor.getLong(0)))
                        }
                    }
                    val live = AppDatabase.get(context)
                    val oldPhotos = live.openHelper.readableDatabase.query("SELECT filePath FROM attachments").use { cursor ->
                        buildList { while (cursor.moveToNext()) add(File(cursor.getString(0))) }
                    }
                    val oldAlarms = live.openHelper.readableDatabase.query(
                        "SELECT id FROM notes WHERE reminderAt IS NOT NULL OR taskCountdownEndAt IS NOT NULL"
                    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) } }
                    live.runInTransaction {
                        val target = live.openHelper.writableDatabase
                        tables.asReversed().forEach { target.execSQL("DELETE FROM `$it`") }
                        tables.forEach { copyTable(packed, target, it) }
                        // Local numeric IDs may now belong to different protected notebooks.
                        com.notessync.app.security.ProtectionSession.lockAll()
                    }
                    committed = true
                    // Only after commit may files referenced by the old DB be removed.
                    // Nonessential refresh failures must not report a successful restore as failed.
                    runCatching {
                        val attachmentsRoot = File(context.filesDir, "attachments").canonicalPath + File.separator
                        oldPhotos.filter { it.canonicalPath.startsWith(attachmentsRoot) }.forEach { it.delete() }
                        oldAlarms.forEach {
                            ReminderScheduler.cancel(context, it)
                            ReminderScheduler.cancelTimer(context, it)
                        }
                        reschedule(context, live.openHelper.readableDatabase)
                        CalendarWidgetProvider.refreshAll(context)
                    }
                    backedUpRevision = revision.get()
                } finally { runCatching { source.close() } }
                Result.success(info.publicInfo())
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { Result.failure(error) }
            finally {
                if (!committed) incoming.deleteRecursively()
                temp.deleteRecursively()
            }
        }
    }

    private fun copyTable(source: SupportSQLiteDatabase, target: SupportSQLiteDatabase, table: String) {
        source.query("SELECT * FROM `$table`").use { rows ->
            val columns = rows.columnNames.joinToString(",") { "`$it`" }
            val placeholders = rows.columnNames.joinToString(",") { "?" }
            target.compileStatement("INSERT INTO `$table` ($columns) VALUES ($placeholders)").use { insert ->
                while (rows.moveToNext()) {
                    insert.clearBindings()
                    for (i in 0 until rows.columnCount) when (rows.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> insert.bindNull(i + 1)
                        Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(i + 1, rows.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(i + 1, rows.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(i + 1, rows.getBlob(i))
                        else -> insert.bindString(i + 1, rows.getString(i))
                    }
                    insert.executeInsert()
                }
            }
        }
    }

    private fun reschedule(context: Context, db: SupportSQLiteDatabase) {
        db.query("SELECT n.id, n.title, n.reminderAt, n.taskCountdownEndAt, " +
            "(n.isProtected OR COALESCE(b.isProtected, 0)) FROM notes n " +
            "LEFT JOIN notebooks b ON n.notebookId = b.id WHERE n.isDeleted = 0 AND n.isCompleted = 0").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val title = if (cursor.getInt(4) != 0) uiText("Защищённая запись") else cursor.getString(1).ifBlank { uiText("Без названия") }
                if (!cursor.isNull(2)) ReminderScheduler.schedule(context, id, title, cursor.getLong(2))
                if (!cursor.isNull(3)) ReminderScheduler.scheduleTimer(context, id, title, cursor.getLong(3))
            }
        }
    }

    suspend fun autoBackupIfDue(context: Context, afterNote: Boolean) = withContext(Dispatchers.IO) {
        if (!operation.tryLock()) return@withContext
        try {
            val settings = SettingsRepository.get(context)
            val state = settings.state.value
            val current = revision.get()
            if (!BackupPolicy.isDue(state.backupFrequency, afterNote && current != backedUpRevision,
                    state.lastAutoBackupAt, System.currentTimeMillis())) return@withContext
            val directory = autoDirectories(context).first().apply { check(isDirectory || mkdirs()) }
            val file = File(directory, "notels-auto-${System.currentTimeMillis()}-${UUID.randomUUID()}.zip")
            val pending = File(directory, file.name + ".pending")
            try {
                pending.outputStream().use { createUnlocked(context, it) }
                check(pending.renameTo(file)) { uiText("Не удалось сохранить копию") }
                settings.setLastAutoBackupAt(System.currentTimeMillis())
                backedUpRevision = current
                autoDirectories(context).flatMap { it.listFiles().orEmpty().toList() }
                    .filter { it.isFile && it.name.startsWith("notels-auto-") && it.extension == "zip" }
                    .sortedByDescending { it.lastModified() }.drop(5).forEach { it.delete() }
            } finally { pending.delete() }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A failed copy must not interrupt saving a note. */ }
        finally { operation.unlock() }
    }

    // Preserve access to v1.10.0 copies previously stored in external app storage.
    private fun autoDirectories(context: Context): List<File> = buildList {
        add(File(context.filesDir, "backups"))
        context.getExternalFilesDir(null)?.let { add(File(it, "backups")) }
    }
    private fun temporaryDirectory(context: Context, prefix: String): File =
        File(context.cacheDir, prefix + UUID.randomUUID()).apply { check(mkdirs()) }
    fun suggestedFileName(): String = "NoteLS-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"
    private fun BackupInfo.archiveInfo() = BackupArchive.Info(createdAt, noteCount, dbVersion)
    private fun BackupArchive.Info.publicInfo() = BackupInfo(createdAt, noteCount, dbVersion)
    private fun ZipOutputStream.addFile(name: String, file: File) {
        putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(this) }
        closeEntry()
    }
}
