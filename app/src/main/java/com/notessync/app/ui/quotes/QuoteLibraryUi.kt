package com.notessync.app.ui.quotes

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.CustomQuoteEntity
import com.notessync.app.data.quotes.*
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*

internal class QuoteLibraryUi(private val context: Context, private val scope: CoroutineScope, private val snackbar: SnackbarHostState) {
    private val database = AppDatabase.get(context)
    private val library = QuoteLibrary(database)
    val quotes = database.customQuoteDao().observeAll()
    val pinned = database.customQuoteDao().observePinned()
    var busy by mutableStateOf(false)
    var showEditor by mutableStateOf(false)
    var editing by mutableStateOf<CustomQuoteEntity?>(null)
    var preview by mutableStateOf<QuoteImportPreview?>(null)
    var deleting by mutableStateOf<CustomQuoteEntity?>(null)
    var problem by mutableStateOf<QuoteProblem?>(null)
    fun edit(quote: CustomQuoteEntity? = null) { if (!busy) { editing = quote; problem = null; showEditor = true } }
    private fun changed() { BackupManager.noteChanged(); BackupManager.requestAutoBackup(context, afterNote = true) }
    private fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true; problem = null
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                problem = (error as? QuoteException)?.problem ?: QuoteProblem.SAVE
                if (!showEditor && preview == null && deleting == null) { val message = problem!!.label(); scope.launch { snackbar.showSnackbar(message) } }
            } finally { busy = false }
        }
    }
    fun load(uri: Uri) = action {
        val entries = QuoteImport.readCancellable { context.contentResolver.openInputStream(uri) }
        preview = library.preview(entries)
    }
    fun save(draft: QuoteDraft, onUpdated: () -> Unit) = action {
        library.save(editing, draft); showEditor = false; editing = null; changed(); onUpdated()
    }
    fun import(onUpdated: () -> Unit) {
        val selected = preview ?: return
        action {
            val result = library.import(selected); preview = null; changed(); onUpdated()
            scope.launch { snackbar.showSnackbar(uiText("Добавлено: %1\$s. Пропущено дублей: %2\$s.", result.added, result.duplicates)) }
        }
    }
    fun delete() {
        val selected = deleting ?: return
        action { library.delete(selected); deleting = null; changed() }
    }
    fun pin(quote: CustomQuoteEntity, selected: Boolean) = action {
        if (selected) library.clearPin(quote.id) else {
            library.pin(quote)
            val settings = SettingsRepository.get(context)
            settings.setDrawerQuoteAppearance(settings.state.value.drawerQuote.copy(enabled = true))
        }
        changed()
    }
}

@Composable
internal fun rememberQuoteLibraryUi(snackbar: SnackbarHostState): QuoteLibraryUi {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(context, scope, snackbar) { QuoteLibraryUi(context, scope, snackbar) }
}

@Composable
internal fun QuoteLibraryDialogs(ui: QuoteLibraryUi, onUpdated: () -> Unit) {
    if (ui.showEditor) QuoteEditorDialog(ui.editing?.draft() ?: QuoteDraft(""), ui.busy, ui.problem,
        onSave = { ui.save(it, onUpdated) }, onDismiss = { if (!ui.busy) ui.showEditor = false })
    ui.preview?.let { preview -> QuoteImportDialog(preview, ui.busy, ui.problem, { ui.import(onUpdated) }, { if (!ui.busy) ui.preview = null }) }
    ui.deleting?.let { quote ->
        AlertDialog(onDismissRequest = { if (!ui.busy) ui.deleting = null }, title = { Text(uiText("Удалить цитату?")) }, text = {
            androidx.compose.foundation.layout.Column {
                Text(quote.draft().formatted(), maxLines = 6, overflow = TextOverflow.Ellipsis)
                ui.problem?.let { Text(it.label(), color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = ui::delete, enabled = !ui.busy) { Text(uiText("Удалить")) } },
            dismissButton = { TextButton(onClick = { ui.deleting = null }, enabled = !ui.busy) { Text(uiText("Отмена")) } })
    }
}
