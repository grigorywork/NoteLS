package com.notessync.app.data.backup

import android.content.Context
import android.net.Uri
import java.io.OutputStream

/** Test-only password. Existing persistence assertions now exercise the encrypted public API. */
object EncryptedBackupFixture {
    private fun password() = "fixture-only-пароль-123".toCharArray()
    suspend fun createBackup(context: Context, output: OutputStream): BackupManager.BackupInfo {
        if (BackupManager.identity(context) == null) {
            val secret = password()
            try { BackupManager.configureProtection(context, null, secret) } finally { secret.fill('\u0000') }
        }
        return BackupManager.createBackup(context, output)
    }
    suspend fun restore(context: Context, uri: Uri): Result<BackupManager.BackupInfo> {
        if (BackupManager.identity(context, uri) == null) return BackupManager.restore(context, uri)
        val secret = password()
        try { return BackupManager.unlock(context, uri, secret).use { BackupManager.restore(context, uri, it) } }
        finally { secret.fill('\u0000') }
    }
}
