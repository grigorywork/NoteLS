package com.notessync.app.data.backup.crypto

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Stores only a public recipient and password-encrypted PKCS8 bytes, separate from signing/Room/sync. */
internal class BackupProtection(context: Context) {
    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    suspend fun current(): BackupIdentity? = withContext(Dispatchers.IO) {
        val value = prefs.getString("identity", null) ?: return@withContext null
        require(value.length <= 16 * 1024)
        json.decodeFromString(BackupIdentity.serializer(), value).also { it.validate() }
    }
    suspend fun configure(oldPassword: CharArray?, password: CharArray): BackupIdentity = withContext(Dispatchers.IO) {
        val old = current()
        val next = if (old == null) BackupIdentity.create(password)
            else old.unlock(requireNotNull(oldPassword)).use { old.changePassword(it, password) }
        save(next); next
    }
    internal suspend fun save(identity: BackupIdentity) = withContext(Dispatchers.IO) {
        identity.validate()
        check(prefs.edit().putString("identity", json.encodeToString(BackupIdentity.serializer(), identity)).commit())
    }
    companion object { const val PREFS = "backup_security" }
}
