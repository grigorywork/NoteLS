package com.notessync.app.ui.quotes

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.quotes.QuoteLibrary
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.DrawerQuoteAppearance
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun DrawerQuoteSettingsDialog(initial: DrawerQuoteAppearance, settings: SettingsRepository, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val database = remember(context) { AppDatabase.get(context) }
    val pinned by remember(database) { database.customQuoteDao().observePinned() }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    DrawerQuoteDialog(initial, onSave = { selected ->
        if (!busy) {
            busy = true; problem = null
            scope.launch {
                try {
                    if (selected.customText != initial.customText) {
                        QuoteLibrary(database).clearPin()
                        BackupManager.noteChanged(); BackupManager.requestAutoBackup(context, afterNote = true)
                    }
                    settings.setDrawerQuoteAppearance(selected); onDismiss()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { problem = uiText("Не удалось сохранить цитату") }
                finally { busy = false }
            }
        }
    }, onDismiss = { if (!busy) onDismiss() }, pinnedText = pinned?.draft()?.formatted(), busy = busy, problem = problem)
}
