package com.notessync.app.data.backup

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val info = BackupArchive.Info(1_700_000_000_000, 3, 10)
    private val database = "SQLite format 3\u0000sample".toByteArray()

    private fun archive(vararg extra: Pair<String, ByteArray>,
                        manifest: String = BackupArchive.manifest(info), db: ByteArray = database): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            (listOf("backup.json" to manifest.toByteArray(), "notessync.db" to db) + extra).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private fun extract(bytes: ByteArray) = BackupArchive.extract(ByteArrayInputStream(bytes), temporary.newFolder())

    @Test fun `v1 backup preserves database and nested photo bytes`() {
        val folder = temporary.newFolder()
        val bytes = archive("attachments/restored-123/photo" to byteArrayOf(4, 5, 6))
        assertEquals(info, BackupArchive.extract(ByteArrayInputStream(bytes), folder))
        assertArrayEquals(database, folder.resolve("notessync.db").readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), folder.resolve("attachments/restored-123/photo").readBytes())
    }
    @Test fun `preview reads only manifest`() {
        assertEquals(info, BackupArchive.inspect(ByteArrayInputStream(archive())))
    }
    @Test fun `rejects a different application's archive`() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive(manifest = BackupArchive.manifest(info).replace("notels-backup", "other-backup")))
        }
    }
    @Test fun `rejects unsupported format`() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive(manifest = BackupArchive.manifest(info).replace("\"format\":1", "\"format\":2")))
        }
    }
    @Test fun `rejects path traversal even when sibling shares directory prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive("attachments/../../escaped/photo" to byteArrayOf(1)))
        }
    }
    @Test fun `rejects Windows path separators`() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive("attachments/..\\escaped" to byteArrayOf(1)))
        }
    }
    @Test fun `rejects invalid SQLite header including terminator`() {
        assertThrows(IllegalArgumentException::class.java) {
            extract(archive(db = "SQLite format 3Xsample".toByteArray()))
        }
    }
    @Test fun `enforces limit on decompressed bytes`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.extract(ByteArrayInputStream(archive("attachments/photo" to ByteArray(1024 * 1024))),
                temporary.newFolder(), maxBytes = 2048)
        }
    }
    @Test fun `rejects oversized manifest before parsing`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.inspect(ByteArrayInputStream(archive(manifest = " ".repeat(70_000))))
        }
    }
    @Test fun `rebinds v1 paths from data data and data user installations`() {
        assertEquals("image", BackupArchive.relativeAttachmentPath("/data/data/com.notessync.app/files/attachments/image"))
        assertEquals("restored-1/image", BackupArchive.relativeAttachmentPath("/data/user/10/com.notessync.app/files/attachments/restored-1/image"))
    }
    @Test fun `rejects malicious paths stored in attachment rows`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.relativeAttachmentPath("/data/data/app/files/attachments/../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchive.relativeAttachmentPath("attachments/a/../other/attachments/photo")
        }
    }
}
