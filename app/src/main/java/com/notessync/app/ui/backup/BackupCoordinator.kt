package com.notessync.app.ui.backup

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.material3.SnackbarHostState
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.backup.crypto.*
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import java.io.File
import javax.crypto.Cipher

internal enum class BackupPasswordMode { SETUP, CHANGE, UNLOCK, ENROLL }

/** Owns transient unlocks and serialized UI actions; passwords never enter SavedState. */
@Stable
internal class BackupCoordinator(context: Context, private val scope: CoroutineScope,
    private val settings: SettingsRepository, private val snackbar: SnackbarHostState,
    internal val vault: BackupBiometricVault = BackupBiometricVault(context)) {
    private val context = context.applicationContext
    var busy by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var identity by mutableStateOf<BackupIdentity?>(null); private set
    var biometricEnabled by mutableStateOf(false); private set
    var copies by mutableStateOf<List<BackupManager.LocalBackup>>(emptyList()); private set
    var showCopies by mutableStateOf(false)
    var passwordMode by mutableStateOf<BackupPasswordMode?>(null)
    var confirmRestore by mutableStateOf(false); private set
    var restoreInfo by mutableStateOf<BackupManager.BackupInfo?>(null); private set
    var challenge by mutableStateOf<BackupBiometricVault.Challenge?>(null); private set
    var restoreBiometric by mutableStateOf(false); private set
    private var restoreUri: Uri? = null
    private var access: BackupAccess? = null
    private var generation = 0L
    private var active = true
    var exportFile: File? = null

    suspend fun refresh() {
        try {
            identity = BackupManager.identity(context)
            biometricEnabled = identity?.let { vault.enabled(it.id) } == true
            copies = BackupManager.localBackups(context)
            loaded = true
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { loaded = false; message(uiText("Не удалось прочитать защиту копий")) }
    }
    private fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    private fun task(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { busy = false; message(uiText("Операция не выполнена. Проверьте пароль или файл и повторите.")) }
            finally { busy = false }
        }
    }
    fun create(uri: Uri) = task {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { BackupManager.createBackup(context, it) }
                ?: error("Output unavailable")
        }
        settings.setLastBackupAt(System.currentTimeMillis())
        busy = false; message(uiText("Бэкап сохранён"))
    }
    fun export(uri: Uri, file: File) = task {
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { BackupManager.exportCopy(context, file, it) }
                ?: error("Output unavailable")
        }
        busy = false; message(uiText("Копия сохранена в выбранную папку"))
    }
    fun select(uri: Uri) = task {
        val token = ++generation
        val info = BackupManager.inspect(context, uri) ?: error("Invalid backup")
        val recipient = BackupManager.identity(context, uri)
        val enrolled = recipient?.let { vault.enabled(it.id) } == true
        if (!active || token != generation) return@task
        access?.close(); access = null
        restoreUri = uri; restoreInfo = info
        restoreBiometric = enrolled
        if (info.encrypted) passwordMode = BackupPasswordMode.UNLOCK else confirmRestore = true
    }
    fun password(mode: BackupPasswordMode, old: CharArray, fresh: CharArray) {
        if (busy || !active || passwordMode != mode) { old.fill('\u0000'); fresh.fill('\u0000'); return }
        if (mode == BackupPasswordMode.ENROLL) { biometric(old); fresh.fill('\u0000'); return }
        val token = generation
        task {
            try {
                when (mode) {
                    BackupPasswordMode.SETUP, BackupPasswordMode.CHANGE -> {
                        BackupManager.configureProtection(context, old.takeIf { mode == BackupPasswordMode.CHANGE }, fresh)
                        passwordMode = null
                        refresh()
                        // Retain the only old copy on conversion failure; allow an explicit retry.
                        BackupManager.protectLocalCopies(context); refresh()
                        busy = false; message(uiText("Новые копии зашифрованы. Старые выгруженные файлы сохраняют прежний пароль."))
                    }
                    BackupPasswordMode.UNLOCK -> {
                        var unlocked: BackupAccess? = null
                        try {
                            withContext(NonCancellable + Dispatchers.IO) { unlocked = BackupManager.unlock(context, requireNotNull(restoreUri), old) }
                            if (active && token == generation) {
                                access?.close(); access = unlocked; unlocked = null
                                passwordMode = null; confirmRestore = true
                            }
                        } finally { unlocked?.close() }
                    }
                    else -> Unit
                }
            } finally { old.fill('\u0000'); fresh.fill('\u0000') }
        }
    }
    fun protectOld() = task { BackupManager.protectLocalCopies(context); refresh() }
    fun disableBiometric() = task { vault.disable(); refresh() }
    fun biometric(password: CharArray? = null) {
        if (busy || !active || passwordMode != (if (password == null) BackupPasswordMode.UNLOCK else BackupPasswordMode.ENROLL)) { password?.fill('\u0000'); return }
        val token = generation
        task {
            var prepared: BackupBiometricVault.Challenge? = null
            try {
                val recipient = requireNotNull(if (password == null) BackupManager.identity(context, requireNotNull(restoreUri)) else identity)
                withContext(NonCancellable + Dispatchers.IO) {
                    prepared = if (password != null) recipient.unlock(password).use { vault.prepare(recipient, it) }
                    else vault.prepare(recipient)
                }
                if (active && token == generation) { challenge = prepared; prepared = null }
            } finally {
                password?.fill('\u0000')
                prepared?.let { withContext(NonCancellable) { vault.discard(it) } }
            }
        }
    }
    fun authenticated(request: BackupBiometricVault.Challenge, cipher: Cipher) {
        if (challenge !== request || !active || busy) return
        challenge = null
        val token = generation
        task {
            var unlocked: BackupAccess? = null
            try {
                withContext(NonCancellable + Dispatchers.IO) { unlocked = vault.complete(request, cipher) }
                if (active && token == generation) {
                    if (request.enroll) { passwordMode = null; refresh() }
                    else { access?.close(); access = unlocked; unlocked = null; passwordMode = null; confirmRestore = true }
                }
            } finally {
                unlocked?.close()
                withContext(NonCancellable) { vault.discard(request) }
            }
        }
    }
    fun cancelBiometric(request: BackupBiometricVault.Challenge) {
        if (challenge !== request) return
        challenge = null; request.invalidate()
        scope.launch(NonCancellable) { vault.discard(request) }
    }
    fun cancelRestore() {
        generation++; access?.close(); access = null
        passwordMode = null; confirmRestore = false; restoreUri = null; restoreInfo = null
    }
    fun restore() {
        if (busy) return
        val uri = restoreUri ?: return
        val lease = access; access = null
        confirmRestore = false; restoreUri = null; restoreInfo = null
        task {
            try {
                val result = BackupManager.restore(context, uri, lease)
                check(result.isSuccess)
                busy = false; message(uiText("Данные восстановлены. Автообмен приостановлен."))
            } finally { lease?.close() }
        }
    }
    fun started() { active = true }
    fun stopped() {
        active = false
        challenge?.let(::cancelBiometric)
        cancelRestore()
    }
}
