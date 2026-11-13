package com.notessync.app.ui.backup

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.common.formatDateTime
import java.io.File

@Composable
internal fun rememberBackupCoordinator(settings: SettingsRepository, snackbar: SnackbarHostState): BackupCoordinator {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = remember(context, settings, snackbar) { BackupCoordinator(context, scope, settings, snackbar) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner, lifecycle) {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) owner.started() else owner.stopped()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) owner.started()
            if (event == Lifecycle.Event.ON_STOP) owner.stopped()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); owner.stopped() }
    }
    return owner
}

@Composable
internal fun BackupDialogs(owner: BackupCoordinator, onExport: (File) -> Unit) {
    owner.passwordMode?.let { mode ->
        BackupPasswordDialog(mode, owner.busy, owner.restoreBiometric,
            onBiometric = { owner.biometric() },
            onSubmit = { old, fresh -> owner.password(mode, old, fresh) }, onDismiss = owner::cancelRestore)
    }
    owner.challenge?.let { BackupBiometricPrompt(it, owner) }
    if (owner.showCopies) AlertDialog(onDismissRequest = { owner.showCopies = false },
        title = { Text(uiText("Копии на устройстве")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (owner.copies.isEmpty()) Text(uiText("Автоматических копий пока нет"))
                owner.copies.forEach { copy ->
                    Text(formatDateTime(copy.info.createdAt), style = MaterialTheme.typography.titleSmall)
                    Text(if (copy.info.encrypted) uiText("Зашифрованная копия") else uiText("Старая копия без шифрования"))
                    Row {
                        TextButton(enabled = !owner.busy, onClick = { owner.showCopies = false; owner.select(Uri.fromFile(copy.file)) }) { Text(uiText("Восстановить")) }
                        TextButton(enabled = !owner.busy && owner.identity != null, onClick = { owner.showCopies = false; onExport(copy.file) }) { Text(uiText("Сохранить в папку")) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { owner.showCopies = false }) { Text(uiText("Закрыть")) } })
    if (owner.confirmRestore) owner.restoreInfo?.let { info ->
        AlertDialog(onDismissRequest = owner::cancelRestore,
            title = { Text(uiText("Восстановить резервную копию?")) },
            text = {
                Column {
                    Text(uiText("Бэкап от %1\$s", formatDateTime(info.createdAt)))
                    if (!info.encrypted) Text(uiText("Старая копия без шифрования. Её содержимое может прочитать любой получивший файл."), color = MaterialTheme.colorScheme.error)
                    Text(uiText(".\n\nТекущие заметки, задачи и фото будут полностью заменены содержимым архива."))
                }
            },
            confirmButton = { TextButton(enabled = !owner.busy, onClick = owner::restore) { Text(uiText("Восстановить")) } },
            dismissButton = { TextButton(onClick = owner::cancelRestore) { Text(uiText("Отмена")) } })
    }
}
