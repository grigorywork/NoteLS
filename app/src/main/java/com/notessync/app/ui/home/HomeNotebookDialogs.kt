package com.notessync.app.ui.home

import androidx.compose.material3.DrawerState
import com.notessync.app.i18n.uiText
import com.notessync.app.security.biometrics.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.ui.common.SetPasswordDialog
import com.notessync.app.ui.common.UnlockPasswordDialog
import com.notessync.app.ui.tasks.*
import com.notessync.app.ui.notes.NoteFilter
import com.notessync.app.ui.notes.NotesViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeNotebookDialogs(viewModel: NotesViewModel, notebookUi: HomeNotebookState,
    notebooks: List<NotebookEntity>, biometricBindings: Map<String, String>, biometricBusy: Boolean,
    biometricRequest: NotebookBiometricController.Request?, drawerState: DrawerState, scope: CoroutineScope,
    snackbarHostState: SnackbarHostState, onOpenEditor: (Long, Boolean) -> Unit) {
    if (notebookUi.showCreateNotebook) {
        NotebookNameDialog(
            title = uiText("Новый блокнот"),
            initial = "",
            confirmLabel = uiText("Создать"),
            onConfirm = { viewModel.createNotebook(it) },
            onDismiss = { notebookUi.showCreateNotebook = false }
        )
    }

    notebookUi.notebookToEdit?.let { notebook ->
        NotebookNameDialog(
            title = uiText("Переименовать"),
            initial = notebook.name,
            confirmLabel = uiText("Сохранить"),
            onConfirm = { viewModel.renameNotebook(notebook, it) },
            onDismiss = { notebookUi.notebookToEdit = null }
        )
    }

    notebookUi.notebookToDelete?.let { notebook ->
        AlertDialog(
            onDismissRequest = { notebookUi.notebookToDelete = null },
            title = { Text(uiText("Удалить блокнот?")) },
            text = { Text(uiText("Заметки останутся в «Все заметки».")) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteNotebook(notebook)
                    notebookUi.notebookToDelete = null
                }) { Text(uiText("Удалить")) }
            },
            dismissButton = {
                TextButton(onClick = { notebookUi.notebookToDelete = null }) { Text(uiText("Отмена")) }
            }
        )
    }
    notebookUi.notebookToProtect?.let { notebook ->
        SetPasswordDialog(
            title = uiText("Пароль блокнота"),
            onDismiss = { notebookUi.notebookToProtect = null },
            onConfirm = { password ->
                viewModel.protectNotebook(notebook, password)
                notebookUi.notebookToProtect = null
            }
        )
    }

    notebookUi.notebookToEnrollBiometric?.let { notebook ->
        UnlockPasswordDialog(title = uiText("Включить биометрию: пароль блокнота"), showError = notebookUi.biometricPasswordError,
            onDismiss = { notebookUi.notebookToEnrollBiometric = null; viewModel.biometrics.cancel() }, busy = biometricBusy,
            onConfirm = { password -> viewModel.biometrics.prepare(notebook, true, password) { ready ->
                when (ready) {
                    NotebookBiometricController.PrepareResult.READY -> notebookUi.notebookToEnrollBiometric = null
                    NotebookBiometricController.PrepareResult.PASSWORD -> notebookUi.biometricPasswordError = true
                    else -> {
                        notebookUi.notebookToEnrollBiometric = null
                        scope.launch { snackbarHostState.showSnackbar(uiText("Биометрия недоступна. Откройте блокнот паролем и повторите.")) }
                    }
                }
            } }
        )
    }
    biometricRequest?.let { request ->
        NotebookBiometricPrompt(request,
            onAuthenticated = { cipher -> viewModel.biometrics.authenticate(request, cipher) { accepted ->
                if (accepted && !request.challenge.enroll) {
                    notebookUi.notebookToUnlock = null
                    val book = notebooks.firstOrNull { it.id == request.notebookId }
                    if (book != null) { viewModel.selectFilter(NoteFilter.Notebook(book.id, book.name)); scope.launch { drawerState.close() } }
                } else scope.launch { snackbarHostState.showSnackbar(if (accepted) uiText("Биометрия включена") else uiText("Биометрия не подтверждена. Используйте пароль.")) }
            } },
            onFallback = { viewModel.biometrics.cancelIfPending(request) },
            onDisposed = { viewModel.biometrics.cancelIfPending(request) })
    }

    notebookUi.notebookToUnlock?.let { notebook ->
        val biometricTag = remember(notebook) { NotebookBiometricBinding.from(notebook)?.credentialTag }
        UnlockPasswordDialog(
            title = uiText("Открыть «%1\$s»", (notebook.name)),
            showError = notebookUi.notebookUnlockError,
            busy = biometricBusy,
            alternative = {
                if (biometricTag != null && biometricBindings[notebook.syncId] == biometricTag) TextButton(
                    enabled = !biometricBusy,
                    onClick = { viewModel.biometrics.prepare(notebook, false) { ready -> if (ready != NotebookBiometricController.PrepareResult.READY) scope.launch { snackbarHostState.showSnackbar(uiText("Биометрия недоступна. Введите пароль.")) } } }
                ) { Text(uiText("Открыть по биометрии")) }
            },
            onDismiss = { notebookUi.notebookToUnlock = null; viewModel.biometrics.cancel() },
            onConfirm = { password ->
                viewModel.unlockNotebook(notebook, password) { unlocked ->
                    if (unlocked) {
                        notebookUi.notebookToUnlock = null
                        viewModel.selectFilter(NoteFilter.Notebook(notebook.id, notebook.name))
                        scope.launch { drawerState.close() }
                    } else {
                        notebookUi.notebookUnlockError = true
                    }
                }
            }
        )
    }

    notebookUi.notebookToUnprotect?.let { notebook ->
        UnlockPasswordDialog(
            title = uiText("Снять защиту блокнота"),
            showError = notebookUi.notebookRemoveError,
            onDismiss = { notebookUi.notebookToUnprotect = null },
            onConfirm = { password ->
                viewModel.removeNotebookProtection(notebook, password) { verified ->
                    if (verified) notebookUi.notebookToUnprotect = null else notebookUi.notebookRemoveError = true
                }
            }
        )
    }

    val taskBusy by viewModel.taskPasswords.busy.collectAsStateWithLifecycle()
    notebookUi.taskToUnlock?.let { note ->
        UnlockPasswordDialog(
            title = uiText("Открыть защищённую задачу"),
            showError = notebookUi.taskUnlockError,
            busy = taskBusy,
            onDismiss = { notebookUi.taskToUnlock = null; viewModel.taskPasswords.cancel() },
            onConfirm = { password ->
                viewModel.unlockTask(note.id, note.syncId, note.isDeleted, password) { unlocked ->
                    if (unlocked) {
                        notebookUi.taskToUnlock = null
                        if (!note.isDeleted) onOpenEditor(note.id, true)
                    } else {
                        notebookUi.taskUnlockError = true
                    }
                }
            }
        )
    }
}

@Composable
private fun NotebookNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(uiText("Название")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) { onConfirm(name); onDismiss() } },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(uiText("Отмена")) }
        }
    )
}
