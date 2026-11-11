package com.notessync.app.data.backup

import android.content.Context
import android.net.Uri
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.BackupFrequency
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Этап 39: резервное копирование заметок.
// Бэкап — единый ZIP: manifest (метаданные), база Room (после WAL-checkpoint)
// и каталог вложений с фото. Ручной экспорт — через системный выбор файла (SAF),
// автобэкап — тихо в Android/data/<приложение>/files/backups (храним 5 последних).
object BackupManager {

    private const val MANIFEST_ENTRY = "backup.json"
    private const val DB_ENTRY = "notessync.db"
    private const val ATTACH_PREFIX = "attachments/"
    private const val BACKUP_KIND = "notels-backup"
    private const val BACKUP_FORMAT = 1
    private const val MAX_AUTO_BACKUPS = 5
    private const val SQLITE_MAGIC = "SQLite format 3"

    data class BackupInfo(
        val createdAt: Long,
        val noteCount: Long,
        val dbVersion: Int
    )

    /** Создать бэкап в произвольный поток (SAF-файл, выбранный пользователем). */
    suspend fun createBackup(context: Context, out: OutputStream): BackupInfo =
        withContext(Dispatchers.IO) {
            val db = AppDatabase.get(context)
            // Сбрасываем WAL в основной файл, чтобы копия была целостной.
            db.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            val dbFile = context.getDatabasePath(DB_ENTRY)
            val noteCount = db.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM notes WHERE isDeleted = 0").use { c ->
                    if (c.moveToFirst()) c.getLong(0) else 0L
                }
            val dbVersion = db.openHelper.readableDatabase.version
            val createdAt = System.currentTimeMillis()

            ZipOutputStream(out.buffered()).use { zos ->
                zos.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zos.write(manifest(createdAt, noteCount, dbVersion).toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry(DB_ENTRY))
                dbFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()

                val attachments = File(context.filesDir, "attachments")
                if (attachments.isDirectory) {
                    attachments.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(attachments).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry(ATTACH_PREFIX + rel))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            BackupInfo(createdAt, noteCount, dbVersion)
        }

    /** Прочитать манифест бэкапа без полной распаковки (для диалога подтверждения). */
    suspend fun inspect(context: Context, uri: Uri): BackupInfo? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    var info: BackupInfo? = null
                    var hasDb = false
                    while (entry != null) {
                        when (entry.name) {
                            MANIFEST_ENTRY -> {
                                val json = JSONObject(zis.readBytes().decodeToString())
                                if (json.optString("kind") == BACKUP_KIND) {
                                    info = BackupInfo(
                                        createdAt = json.optLong("createdAt"),
                                        noteCount = json.optLong("noteCount"),
                                        dbVersion = json.optInt("dbVersion")
                                    )
                                }
                            }
                            DB_ENTRY -> hasDb = true
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    info?.takeIf { hasDb }
                }
            }
        }.getOrNull()
    }

    /**
     * Восстановить бэкап: распаковать во временный каталог, проверить SQLite-заголовок,
     * закрыть Room и подменить файлы. После успеха приложение нужно перезапустить.
     */
    suspend fun restore(context: Context, uri: Uri): Result<BackupInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val tmp = File(context.cacheDir, "restore_tmp")
                tmp.deleteRecursively()
                tmp.mkdirs()

                context.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name == MANIFEST_ENTRY || name == DB_ENTRY ||
                                name.startsWith(ATTACH_PREFIX)
                            ) {
                                val out = File(tmp, name)
                                // Защита от path traversal внутри архива.
                                check(out.canonicalPath.startsWith(tmp.canonicalPath)) {
                                    "Некорректный архив"
                                }
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zis.copyTo(it) }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                } ?: error("Не удалось открыть файл")

                val dbFile = File(tmp, DB_ENTRY)
                check(dbFile.exists()) { "В архиве нет базы заметок" }
                dbFile.inputStream().use { ins ->
                    val magic = ByteArray(16)
                    val read = ins.read(magic)
                    check(read == 16 && String(magic, 0, 15) == SQLITE_MAGIC) {
                        "Файл не похож на базу NoteLS"
                    }
                }

                val info = runCatching {
                    val json = JSONObject(File(tmp, MANIFEST_ENTRY).readText())
                    BackupInfo(
                        createdAt = json.optLong("createdAt"),
                        noteCount = json.optLong("noteCount"),
                        dbVersion = json.optInt("dbVersion")
                    )
                }.getOrDefault(BackupInfo(0L, -1L, 0))

                // Подмена файлов: Room должен быть закрыт до замены файла БД.
                AppDatabase.closeInstance()
                val live = context.getDatabasePath(DB_ENTRY)
                live.delete()
                File(live.path + "-wal").delete()
                File(live.path + "-shm").delete()
                File(live.path + "-journal").delete()
                dbFile.copyTo(live, overwrite = true)

                val liveAttachments = File(context.filesDir, "attachments")
                liveAttachments.deleteRecursively()
                val packedAttachments = File(tmp, "attachments")
                if (packedAttachments.isDirectory) {
                    packedAttachments.copyRecursively(liveAttachments, overwrite = true)
                } else {
                    liveAttachments.mkdirs()
                }

                tmp.deleteRecursively()
                info
            }
        }

    /**
     * Автобэкап по настройке частоты. Вызывается при старте приложения
     * (afterNote=false) и после сохранения заметки (afterNote=true).
     * Тихий: любые ошибки проглатываются, чтобы не мешать работе.
     */
    suspend fun autoBackupIfDue(context: Context, afterNote: Boolean) {
        val settings = SettingsRepository.get(context)
        val state = settings.state.value
        val due = when (state.backupFrequency) {
            BackupFrequency.MANUAL -> false
            BackupFrequency.AFTER_NOTE -> afterNote
            else -> System.currentTimeMillis() - state.lastBackupAt >=
                state.backupFrequency.intervalMillis
        }
        if (!due) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "notels-auto-$stamp.zip")
            file.outputStream().use { createBackup(context, it) }
            settings.setLastBackupAt(System.currentTimeMillis())
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_AUTO_BACKUPS)
                ?.forEach { it.delete() }
        }
    }

    /** Имя для ручного бэкапа: NoteLS-backup-20261111-1530.zip */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "NoteLS-backup-$stamp.zip"
    }

    private fun manifest(createdAt: Long, noteCount: Long, dbVersion: Int): String =
        JSONObject()
            .put("kind", BACKUP_KIND)
            .put("format", BACKUP_FORMAT)
            .put("createdAt", createdAt)
            .put("noteCount", noteCount)
            .put("dbVersion", dbVersion)
            .toString()
}
