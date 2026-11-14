package com.notessync.app.data.backup.audit

import com.notessync.app.data.backup.BackupArchive
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Safe small fixture proves a directory entry cannot bypass the total decompressed-byte budget. */
class BackupDirectoryBudgetRegressionTest {
    @Test fun `directory payload counts against the same extraction budget as ordinary files`() {
        val archive = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupArchive.MANIFEST))
            zip.write(BackupArchive.manifest(BackupArchive.Info(1000, 0, 17)).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(BackupArchive.DATABASE)); zip.write("SQLite format 3\u0000".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("attachments/directory/"))
            zip.write(ByteArray(32 * 1024)); zip.closeEntry()
        } }.toByteArray()
        val target = Files.createTempDirectory("directory-budget-audit").toFile()
        try {
            assertTrue("32KiB directory payload must not pass a256-byte extraction budget",
                runCatching { BackupArchive.extract(archive.inputStream(), target, maxBytes = 256) }.isFailure)
        } finally { target.deleteRecursively() }
    }
}
