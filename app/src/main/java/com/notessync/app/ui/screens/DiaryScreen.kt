package com.notessync.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.data.local.model.NoteIdentity
import com.notessync.app.i18n.uiText
import com.notessync.app.notes.diary.*
import com.notessync.app.security.ProtectionSession
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(onBack: () -> Unit, onOpenNote: (Long) -> Unit, viewModel: DiaryViewModel = viewModel(),
    imeInsets: WindowInsets = WindowInsets.ime) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val book by viewModel.book.collectAsStateWithLifecycle()
    val access by ProtectionSession.state.collectAsStateWithLifecycle()
    val result by viewModel.rows.collectAsStateWithLifecycle()
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    val page by viewModel.page.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var protectedAction by remember { mutableStateOf(false) }
    var protectedRow by remember { mutableStateOf<DiaryRow?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val unlocked = book?.let { DiaryPrivacy.valid(it) && it.id in access.notebookIds } == true
    val shown = result?.takeIf { unlocked && it.book == book && it.trash == trash && it.page == page }?.rows.orEmpty()
    fun back() { viewModel.lock(); onBack() }
    BackHandler { back() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        password = ""; confirmation = ""; protectedRow = null; migrating = false; changingPassword = false; menu = false; viewModel.stopped()
    }
    DisposableEffect(viewModel) { onDispose { viewModel.stopped() } }
    LaunchedEffect(book?.id, unlocked) { password = ""; confirmation = ""; protectedRow = null; migrating = false; changingPassword = false }
    LaunchedEffect(message) {
        message?.let {
            // Keep the effect key stable until the snackbar completes; clearing first cancels it.
            snackbar.showSnackbar(uiText(it))
            viewModel.clearMessage(it)
        }
    }
    Scaffold(
        modifier = Modifier.windowInsetsPadding(imeInsets),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopAppBar(title = { Text(uiText(if (trash) "Корзина дневника" else "Дневник")) },
            navigationIcon = { IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Назад")) } },
            actions = {
                if (unlocked) {
                    IconButton(onClick = viewModel::lock) { Icon(Icons.Default.Lock, uiText("Заблокировать дневник")) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, uiText("Действия дневника")) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(uiText(if (trash) "Записи дневника" else "Корзина дневника")) },
                                onClick = { menu = false; viewModel.showTrash(!trash) })
                            DropdownMenuItem(text = { Text(uiText("Сменить пароль дневника")) },
                                onClick = { menu = false; changingPassword = true })
                            DropdownMenuItem(text = { Text(uiText("Перенести прежние записи")) },
                                onClick = { menu = false; migrating = true; viewModel.showMigration(true) })
                        }
                    }
                }
            }) },
        floatingActionButton = { if (unlocked && !trash) FloatingActionButton(onClick = { viewModel.createEntry(onOpenNote) }) {
            Icon(Icons.Default.Add, uiText("Новая запись дневника"))
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (books == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            if (books.orEmpty().size > 1) {
                var selectBook by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { selectBook = true }) { Text(uiText("Дневник %1\$s", books.orEmpty().indexOfFirst { it.id == book?.id } + 1)) }
                    DropdownMenu(selectBook, { selectBook = false }) {
                        books.orEmpty().forEachIndexed { index, item -> DropdownMenuItem(
                            text = { Text(uiText("Дневник %1\$s", index + 1)) }, onClick = { selectBook = false; viewModel.select(item.id) }) }
                    }
                }
            }
            if (!unlocked) {
                val setup = books.orEmpty().isEmpty()
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary)
                    Text(uiText(if (setup) "Задайте пароль дневника" else "Дневник закрыт"), style = MaterialTheme.typography.titleLarge)
                    Text(uiText("Записи дневника хранятся отдельно. Закройте дневник или нажмите замок, чтобы заблокировать его."))
                    OutlinedTextField(password, { password = it.take(128) }, label = { Text(uiText("Пароль")) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("diary-password"))
                    if (setup) {
                        OutlinedTextField(confirmation, { confirmation = it.take(128) }, label = { Text(uiText("Повторите пароль")) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("diary-confirm"))
                        Text(uiText("Сохраните пароль. Восстановить его нельзя. Прежние записи можно перенести после создания дневника."), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(enabled = !busy && password.isNotEmpty(), onClick = {
                        val value = password; val repeat = confirmation; password = ""; confirmation = ""
                        if (setup) viewModel.setup(value, repeat) else viewModel.unlock(value)
                    }, modifier = Modifier.testTag("diary-enter")) { Text(uiText(if (setup) "Создать дневник" else "Открыть дневник")) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                return@Column
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!trash && shown.isEmpty()) {
                Text(uiText("В дневнике пока нет записей"), modifier = Modifier.padding(vertical = 20.dp))
                TextButton(onClick = { migrating = true; viewModel.showMigration(true) }) { Text(uiText("Перенести прежние записи")) }
            } else if (trash && shown.isEmpty()) Text(uiText("Корзина дневника пуста"), modifier = Modifier.padding(vertical = 20.dp))
            LazyColumn(Modifier.weight(1f).testTag("diary-list"), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(shown.take(PrivateDiary.PAGE), key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(if (row.isProtected) uiText("Запись с отдельным паролем") else row.title.ifBlank { uiText("Без названия") }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Column {
                            row.dayKey?.let { day -> Text(runCatching { LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("dd.MM.uuuu")) }.getOrDefault("")) }
                            if (row.preview.isNotBlank()) Text(row.preview, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } },
                        leadingContent = { Icon(if (row.isProtected) Icons.Default.Lock else Icons.Default.Book, null) },
                        trailingContent = { IconButton(enabled = !busy, onClick = {
                            if (row.isProtected) { protectedAction = true; protectedRow = row } else viewModel.action(row, trash)
                        }) { Icon(if (trash) Icons.Default.RestoreFromTrash else Icons.Default.Delete, uiText(if (trash) "Восстановить" else "В корзину")) } },
                        modifier = Modifier.testTag("diary-row-${row.id}").clickable(enabled = !busy && !trash) {
                            if (row.isProtected) { protectedAction = false; protectedRow = row } else onOpenNote(row.id)
                        })
                    HorizontalDivider()
                }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = page > 0 && !busy, onClick = viewModel::previous) { Text(uiText("Предыдущие записи")) }
                    TextButton(enabled = shown.size > PrivateDiary.PAGE && !busy, onClick = viewModel::more) { Text(uiText("Следующие записи")) }
                } }
            }
        }
    }
    if (changingPassword && unlocked) {
        var old by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        var repeat by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { changingPassword = false }, title = { Text(uiText("Сменить пароль дневника")) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(old, { old = it.take(128) }, label = { Text(uiText("Текущий пароль дневника")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                OutlinedTextField(replacement, { replacement = it.take(128) }, label = { Text(uiText("Новый пароль дневника")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
                OutlinedTextField(repeat, { repeat = it.take(128) }, label = { Text(uiText("Повторите пароль")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
            }
        }, confirmButton = { TextButton(enabled = !busy && old.isNotEmpty() && replacement.isNotEmpty(), onClick = {
            viewModel.changePassword(old, replacement, repeat); old = ""; replacement = ""; repeat = ""; changingPassword = false
        }) { Text(uiText("Сменить пароль дневника")) } }, dismissButton = { TextButton(onClick = { changingPassword = false }) { Text(uiText("Отмена")) } })
    }
    if (migrating && unlocked) DiaryMigrationDialog(viewModel, onDismiss = { migrating = false; viewModel.showMigration(false) })
    protectedRow?.takeIf { unlocked }?.let { row ->
        var ownPassword by remember(row.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { viewModel.stopped(); ownPassword = ""; protectedRow = null }, title = { Text(uiText("Запись с отдельным паролем")) },
            text = { OutlinedTextField(ownPassword, { ownPassword = it.take(128) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password), singleLine = true, label = { Text(uiText("Пароль")) }) },
            confirmButton = { TextButton(enabled = !busy && ownPassword.isNotEmpty(), onClick = {
                val value = ownPassword; val action = protectedAction; ownPassword = ""; protectedRow = null
                viewModel.openProtected(row, value) { id -> if (action) viewModel.action(row, row.isDeleted) else onOpenNote(id) }
            }) { Text(uiText(if (!protectedAction) "Открыть" else if (row.isDeleted) "Восстановить" else "В корзину")) } },
            dismissButton = { TextButton(onClick = { viewModel.stopped(); ownPassword = ""; protectedRow = null }) { Text(uiText("Отмена")) } })
    }
}

@Composable
private fun DiaryMigrationDialog(viewModel: DiaryViewModel, onDismiss: () -> Unit) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val access by ProtectionSession.state.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val page by viewModel.candidatePage.collectAsStateWithLifecycle()
    val includeTasks by viewModel.includeDatedTasks.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val current = candidates?.takeIf { it.book == book && it.access == access && it.page == page && it.includeTasks == includeTasks }
    val rows = current?.rows.orEmpty().take(PrivateDiary.PAGE)
    var selected by remember { mutableStateOf(emptyMap<Long, String>()) }
    var seen by remember { mutableStateOf(emptySet<Long>()) }
    LaunchedEffect(page, includeTasks) { selected = emptyMap(); seen = emptySet() }
    LaunchedEffect(current) {
        val fresh = rows.associate { it.id to it.syncId }
        selected = selected.filter { fresh[it.key] == it.value } + rows.filter { it.legacyTag && it.id !in seen }.associate { it.id to it.syncId }
        seen = seen + fresh.keys
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Перенести прежние записи")) }, text = {
        Column(Modifier.heightIn(max = 420.dp)) {
            Text(uiText("Записи с тегом «Дневник» отмечены. Остальные записи с датой выбирайте вручную."), style = MaterialTheme.typography.bodySmall)
            Text(uiText("Сначала разблокируйте защищённые записи и их блокноты. Текст, даты, пароли и вложения сохранятся."), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { viewModel.showDatedTasks(!includeTasks) }) {
                Checkbox(includeTasks, onCheckedChange = viewModel::showDatedTasks, enabled = !busy)
                Text(uiText("Показать задачи с датой"), Modifier.weight(1f).padding(vertical = 12.dp))
            }
            if (current == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(rows, key = { it.id }) { row -> Row(Modifier.fillMaxWidth().clickable(enabled = !busy) {
                    selected = if (selected[row.id] == row.syncId) selected - row.id else selected + (row.id to row.syncId)
                }) {
                    Checkbox(selected[row.id] == row.syncId, onCheckedChange = { checked -> selected = if (checked) selected + (row.id to row.syncId) else selected - row.id }, enabled = !busy)
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(row.title.ifBlank { uiText("Без названия") }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (row.isDeleted) Text(uiText("Из корзины"), style = MaterialTheme.typography.labelSmall)
                        else if (row.isTask) Text(uiText("Задача"), style = MaterialTheme.typography.labelSmall)
                    }
                } }
            }
            if (current != null && rows.isEmpty()) Text(uiText("Нет записей для переноса"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = page > 0 && !busy, onClick = viewModel::previousCandidates) { Text(uiText("Назад")) }
                TextButton(enabled = current?.rows.orEmpty().size > PrivateDiary.PAGE && !busy, onClick = viewModel::moreCandidates) { Text(uiText("Далее")) }
            }
        }
    }, confirmButton = { TextButton(enabled = !busy && selected.isNotEmpty() && current != null, onClick = {
        current?.let { viewModel.move(it.book, selected.map { (id, syncId) -> NoteIdentity(id, syncId) }, it.includeTasks) }; onDismiss()
    }) { Text(uiText("Перенести %1\$s", selected.size)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Отмена")) } })
}
