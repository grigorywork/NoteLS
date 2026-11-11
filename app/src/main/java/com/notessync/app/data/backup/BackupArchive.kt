package com.notessync.app.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Archive validation is independent of Android, so corrupt input can be tested on the JVM. */
internal object BackupArchive {
    const val DATABASE = "notessync.db"
    const val MANIFEST = "backup.json"
    const val ATTACHMENTS = "attachments/"
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 100_000
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    data class Info(val createdAt: Long, val noteCount: Long, val dbVersion: Int)

    fun manifest(info: Info): String = buildJsonObject {
        put("kind", "notels-backup")
        put("format", 1)
        put("createdAt", info.createdAt)
        put("noteCount", info.noteCount)
        put("dbVersion", info.dbVersion)
    }.toString()

    fun parseManifest(text: String): Info {
        val json = Json.parseToJsonElement(text).jsonObject
        require(json["kind"]?.jsonPrimitive?.content == "notels-backup") { "Это не резервная копия NoteLS" }
        require(json["format"]?.jsonPrimitive?.int == 1) { "Неподдерживаемый формат резервной копии" }
        return Info(
            json.getValue("createdAt").jsonPrimitive.long,
            json.getValue("noteCount").jsonPrimitive.long,
            json.getValue("dbVersion").jsonPrimitive.int
        ).also {
            require(it.createdAt > 0 && it.noteCount >= 0 && it.dbVersion > 0) { "Повреждён манифест копии" }
        }
    }

    /** Preview stops at the small manifest rather than inflating all the photographs. */
    fun inspect(input: InputStream): Info = ZipInputStream(input.buffered()).use { zip ->
        val entry = zip.nextEntry ?: error("В архиве нет манифеста NoteLS")
        require(entry.name == MANIFEST && !entry.isDirectory) { "В архиве нет манифеста NoteLS" }
        parseManifest(readUpTo(zip, MAX_MANIFEST_BYTES + 1).also {
            require(it.size <= MAX_MANIFEST_BYTES) { "Слишком большой манифест" }
        }.toString(Charsets.UTF_8))
    }

    fun relativeAttachmentPath(path: String): String {
        // v1 databases contain absolute paths belonging to the original installation.
        val normalized = path.replace('\\', '/')
        val relative = if (normalized.startsWith(ATTACHMENTS)) normalized.removePrefix(ATTACHMENTS)
            else normalized.substringAfter("/attachments/", normalized)
        require(relative.isNotBlank() && !relative.startsWith('/') && ':' !in relative &&
            relative.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Некорректный путь фотографии" }
        return relative
    }

    fun extract(input: InputStream, directory: File, maxBytes: Long = MAX_BYTES): Info {
        require(directory.isDirectory || directory.mkdirs()) { "Не удалось подготовить восстановление" }
        val seen = mutableSetOf<String>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(seen.add(name) && seen.size <= MAX_ENTRIES) { "Повторяющиеся файлы в архиве" }
                require('\\' !in name && (name == MANIFEST || name == DATABASE || name.startsWith(ATTACHMENTS))) {
                    "Неизвестный файл в резервной копии"
                }
                if (entry.isDirectory) {
                    require(name.startsWith(ATTACHMENTS)) { "Некорректный архив" }
                    if (name != ATTACHMENTS) relativeAttachmentPath(name.removeSuffix("/"))
                } else {
                    if (name.startsWith(ATTACHMENTS)) relativeAttachmentPath(name)
                    val target = File(directory, name)
                    require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Некорректный путь в архиве" }
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var entrySize = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            entrySize += read
                            require(total <= maxBytes) { "Резервная копия слишком велика" }
                            require(name != MANIFEST || entrySize <= MAX_MANIFEST_BYTES) { "Слишком большой манифест" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry() // Also verifies the ZIP CRC.
            }
        }
        val info = parseManifest(File(directory, MANIFEST).readText())
        val db = File(directory, DATABASE)
        require(db.isFile) { "В архиве нет базы заметок" }
        db.inputStream().use { require(readUpTo(it, 16).contentEquals(sqliteHeader)) { "Повреждена база заметок" } }
        return info
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        while (count < limit) {
            val read = input.read(bytes, count, limit - count)
            if (read < 0) break
            if (read > 0) count += read
        }
        return bytes.copyOf(count)
    }
}
