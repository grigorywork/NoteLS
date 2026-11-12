package com.notessync.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.reminders.ReminderTime
import com.notessync.app.settings.CompletedStyle
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.ui.common.SetPasswordDialog
import com.notessync.app.ui.common.UnlockPasswordDialog
import com.notessync.app.ui.common.formatDateTime
import com.notessync.app.ui.notes.EditorMessage
import com.notessync.app.ui.notes.EditorScaffold
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.PhotoAttachmentGrid
import com.notessync.app.ui.notes.md.MarkdownEdit
import com.notessync.app.ui.notes.md.MarkdownLite
import com.notessync.app.ui.notes.md.MarkdownVisualTransformation
import com.notessync.app.ui.tasks.TaskTimerIndicator
import com.notessync.app.ui.tasks.CountdownIndicator
import com.notessync.app.ui.tasks.taskColorOption
import com.notessync.app.ui.tasks.taskColorOptions
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
    onBack: () -> Unit,
    onDeleted: (Long) -> Unit,
    viewModel: NoteEditorViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val settingsState by remember { SettingsRepository.get(context).state }.collectAsStateWithLifecycle()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showNotebookMenu by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showTimerResetDialog by remember { mutableStateOf(false) }
    var showCountdownMenu by remember { mutableStateOf(false) }
    var showCountdownCustomDialog by remember { mutableStateOf(false) }
    var showTaskPasswordDialog by remember { mutableStateOf(false) }
    var showTaskProtectionMenu by remember { mutableStateOf(false) }
    var showRemoveTaskProtection by remember { mutableStateOf(false) }
    var taskProtectionError by remember { mutableStateOf(false) }
    var showReminderMenu by remember { mutableStateOf(false) }
    var showFormattingPanel by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePickerFor by remember { mutableStateOf<Long?>(null) }
    // Этап 32: голосовой ввод.
    var voiceListening by remember { mutableStateOf(false) }
    var voicePartial by remember { mutableStateOf("") }
    val currentVoiceEngine = rememberUpdatedState(settingsState.voiceEngine)
    val currentVoiceStructure = rememberUpdatedState(settingsState.voiceStructure)
    val voiceManager = remember {
        com.notessync.app.voice.VoiceInputManager(
            context,
            object : com.notessync.app.voice.VoiceInputManager.Listener {
                override fun onReady() {
                    voiceListening = true
                    voicePartial = ""
                }
                override fun onPartial(text: String) {
                    voicePartial = text
                }
                override fun onFinal(text: String) {
                    viewModel.insertVoiceText(text, currentVoiceStructure.value)
                    voicePartial = ""
                }
                override fun onError(message: String) {
                    voicePartial = ""
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
                override fun onEnd() {
                    voiceListening = false
                    voicePartial = ""
                }
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { voiceManager.stop() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        voiceManager.stop()
        voiceListening = false
        voicePartial = ""
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceManager.start(currentVoiceEngine.value)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Без разрешения на микрофон голосовой ввод недоступен")
            }
        }
    }
    fun toggleVoiceInput() {
        if (voiceListening) {
            voiceManager.stop()
            voiceListening = false
            voicePartial = ""
            return
        }
        if (!voiceManager.isAvailable) {
            scope.launch {
                snackbarHostState.showSnackbar("Распознавание речи недоступно на этом устройстве")
            }
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            voiceManager.start(currentVoiceEngine.value)
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::attach) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && ReminderScheduler.canNotify(context)) {
            showReminderMenu = true
        } else {
            viewModel.reportReminderNotificationsDisabled()
        }
    }

    fun openReminderMenu() {
        when {
            ReminderScheduler.canNotify(context) -> showReminderMenu = true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> viewModel.reportReminderNotificationsDisabled()
        }
    }

    LaunchedEffect(viewModel.accessDenied) {
        if (viewModel.accessDenied) onBack()
    }
    LaunchedEffect(viewModel.message) {
        val current = viewModel.message ?: return@LaunchedEffect
        val text = when (current) {
            EditorMessage.FORMATTING_LIMIT -> "Достигнут предел оформления. Текст сохранён; используйте более крупные выделения."
            EditorMessage.PHOTO_TOO_LARGE -> "Фото больше 10 МБ. Выберите изображение меньшего размера."
            EditorMessage.PHOTO_IMPORT_FAILED -> "Не удалось прикрепить фото. Попробуйте другое изображение."
            EditorMessage.REMINDER_IN_PAST -> "Выберите время напоминания в будущем."
            EditorMessage.REMINDER_NOTIFICATIONS_DISABLED ->
                "Уведомления выключены. Разрешите их, чтобы видеть напоминания."
            EditorMessage.REMINDER_SCHEDULE_FAILED ->
                "Не удалось установить напоминание. Попробуйте ещё раз."
        }
        val notificationsDisabled = current == EditorMessage.REMINDER_NOTIFICATIONS_DISABLED
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = if (notificationsDisabled) "Настройки" else null,
            duration = if (notificationsDisabled) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (notificationsDisabled && result == SnackbarResult.ActionPerformed) {
            openNotificationSettings(context)
        }
        viewModel.clearMessage()
    }

    fun exit() {
        scope.launch {
            // Сначала принудительное сохранение (снимает гонку автосохранения),
            // потом удаление пустой черновой заметки.
            viewModel.flushOnExit()
            onBack()
        }
    }
    BackHandler { exit() }

    EditorScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.isTask) "Задача" else "Заметка") },
                navigationIcon = {
                    IconButton(onClick = { exit() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    // Этап 32: микрофон с мелкой подписью AI.
                    IconButton(onClick = { toggleVoiceInput() }) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = if (voiceListening) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                ) { Text("AI", style = MaterialTheme.typography.labelSmall) }
                            }
                        ) {
                            Icon(
                                if (voiceListening) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = "Голосовой ввод AI",
                                tint = if (voiceListening) MaterialTheme.colorScheme.error
                                else LocalContentColor.current
                            )
                        }
                    }
                    IconButton(onClick = viewModel::toggleFavorite) {
                        Icon(
                            if (viewModel.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Избранное",
                            tint = if (viewModel.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Предпросмотр
                    IconButton(onClick = { viewModel.previewMode = !viewModel.previewMode }) {
                        Icon(
                            if (viewModel.previewMode) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Предпросмотр"
                        )
                    }
                    if (viewModel.exists) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Box {
                NavigationBar {
                    NavigationBarItem(
                        selected = showFormattingPanel,
                        onClick = { showFormattingPanel = !showFormattingPanel },
                        icon = { Icon(Icons.Default.FormatBold, contentDescription = null) },
                        label = { Text("Текст") }
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        icon = { Icon(Icons.Default.Image, contentDescription = null) },
                        label = { Text("Фото") }
                    )
                    NavigationBarItem(
                        selected = viewModel.reminderAt != null,
                        onClick = { openReminderMenu() },
                        icon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                        label = { Text("Сигнал") }
                    )
                    NavigationBarItem(
                        selected = viewModel.notebookName != null,
                        onClick = { showNotebookMenu = true },
                        icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                        label = { Text("Блокнот") }
                    )
                    NavigationBarItem(
                        selected = viewModel.noteTags.isNotEmpty(),
                        onClick = { showTagDialog = true },
                        icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                        label = { Text("Теги") }
                    )
                }
                DropdownMenu(
                    expanded = showReminderMenu,
                    onDismissRequest = { showReminderMenu = false }
                ) {
                    // Этап 26: шаблоны сокращены вдвое — только ходовые варианты.
                    listOf(
                        "Через 1 час" to 1,
                        "Через 2 часа" to 2,
                        "Через 6 часов" to 6
                    ).forEach { (label, hours) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                showReminderMenu = false
                                viewModel.setReminder(ReminderTime.afterHours(hours))
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Завтра, 09:00") },
                        onClick = {
                            showReminderMenu = false
                            viewModel.setReminder(ReminderTime.tomorrowMorning())
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Выбрать дату и время") },
                        onClick = {
                            showReminderMenu = false
                            showDatePicker = true
                        }
                    )
                }
                DropdownMenu(
                    expanded = showNotebookMenu,
                    onDismissRequest = { showNotebookMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Без блокнота" + if (viewModel.notebookName == null) " ✓" else "") },
                        onClick = {
                            viewModel.assignNotebook(null)
                            showNotebookMenu = false
                        }
                    )
                    notebooks.forEach { notebook ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    notebook.name + if (viewModel.notebookName == notebook.name) " ✓" else ""
                                )
                            },
                            onClick = {
                                viewModel.assignNotebook(notebook)
                                showNotebookMenu = false
                            }
                        )
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Этап 32: плашка «Слушаю…» с промежуточным текстом.
            if (voiceListening) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(
                            if (voicePartial.isBlank()) "Слушаю… Говорите." else voicePartial,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (attachments.isNotEmpty()) {
                PhotoAttachmentGrid(
                    attachments = attachments,
                    onOpen = { openAttachment(context, it.filePath, it.mimeType) },
                    onRemove = viewModel::removeAttachment
                )
            }
            if (viewModel.noteTags.isNotEmpty()) {
                FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                viewModel.noteTags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.removeTag(tag) },
                        label = { Text(tag) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Убрать тег") },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                }
            }
            FlowRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                FilterChip(
                    selected = viewModel.isTask,
                    onClick = viewModel::toggleTask,
                    label = { Text("Задача") },
                    leadingIcon = { Icon(Icons.Default.CheckBox, contentDescription = null) },
                    modifier = Modifier.padding(end = 8.dp)
                )
                if (viewModel.isTask) {
                    FilterChip(
                        selected = viewModel.isCompleted,
                        onClick = viewModel::toggleCompleted,
                        label = { Text(if (viewModel.isCompleted) "Выполнено" else "Выполнить") },
                        leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    AssistChip(
                        onClick = { showColorDialog = true },
                        label = { Text(taskColorOption(viewModel.taskColor).label) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = null,
                                // Этап 35: насыщенный акцент вместо бледной пастели.
                                tint = taskColorOption(viewModel.taskColor).accent
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    AssistChip(
                        onClick = viewModel::toggleTaskTimer,
                        enabled = !viewModel.isCompleted || viewModel.taskTimerStartedAt != null,
                        label = {
                            Text(
                                when {
                                    viewModel.taskTimerStartedAt != null -> "Пауза"
                                    viewModel.taskElapsedMillis > 0L -> "Продолжить"
                                    else -> "Запустить"
                                }
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (viewModel.taskTimerStartedAt != null) Icons.Default.Pause
                                else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    // Этап 30: обратный отсчёт с шаблонами и своим временем.
                    Box {
                        val countdownActive = viewModel.taskCountdownMillis > 0L ||
                            viewModel.taskCountdownEndAt != null
                        AssistChip(
                            onClick = { showCountdownMenu = true },
                            enabled = !viewModel.isCompleted || countdownActive,
                            label = { Text("Таймер") },
                            leadingIcon = {
                                Icon(Icons.Default.Timer, contentDescription = null)
                            }
                        )
                        DropdownMenu(
                            expanded = showCountdownMenu,
                            onDismissRequest = { showCountdownMenu = false }
                        ) {
                            countdownPresets.forEach { (label, millis) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        showCountdownMenu = false
                                        viewModel.startCountdown(millis)
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Своё время…") },
                                onClick = {
                                    showCountdownMenu = false
                                    showCountdownCustomDialog = true
                                }
                            )
                            if (countdownActive) {
                                if (viewModel.taskCountdownEndAt != null) {
                                    DropdownMenuItem(
                                        text = { Text("Пауза") },
                                        onClick = {
                                            showCountdownMenu = false
                                            viewModel.pauseCountdown()
                                        }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Продолжить") },
                                        onClick = {
                                            showCountdownMenu = false
                                            viewModel.resumeCountdown()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Снять таймер") },
                                    onClick = {
                                        showCountdownMenu = false
                                        viewModel.cancelCountdown()
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        AssistChip(
                            onClick = {
                                if (viewModel.isProtected) showTaskProtectionMenu = true
                                else showTaskPasswordDialog = true
                            },
                            label = { Text(if (viewModel.isProtected) "Защищено" else "Пароль") },
                            leadingIcon = {
                                Icon(
                                    if (viewModel.isProtected) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null
                                )
                            }
                        )
                        DropdownMenu(
                            expanded = showTaskProtectionMenu,
                            onDismissRequest = { showTaskProtectionMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Заблокировать и закрыть") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                onClick = {
                                    showTaskProtectionMenu = false
                                    viewModel.lockTask()
                                    exit()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Снять защиту") },
                                leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) },
                                onClick = {
                                    showTaskProtectionMenu = false
                                    taskProtectionError = false
                                    showRemoveTaskProtection = true
                                }
                            )
                        }
                    }
                }
            }
            if (viewModel.isTask && settingsState.showTaskPriorities) {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    com.notessync.app.ui.tasks.TaskPriorityPicker(viewModel.taskPriority, viewModel::choosePriority)
                }
            }
            if (viewModel.isTask) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TaskTimerIndicator(
                        elapsedMillis = viewModel.taskElapsedMillis,
                        startedAt = viewModel.taskTimerStartedAt,
                        contentColor = taskColorOption(viewModel.taskColor).accent,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { showTimerResetDialog = true },
                        enabled = viewModel.taskElapsedMillis > 0L || viewModel.taskTimerStartedAt != null
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null)
                        Text("Сбросить")
                    }
                }
                // Этап 30: строка обратного отсчёта (когда таймер задан).
                if (viewModel.taskCountdownMillis > 0L || viewModel.taskCountdownEndAt != null) {
                    CountdownIndicator(
                        countdownMillis = viewModel.taskCountdownMillis,
                        endAt = viewModel.taskCountdownEndAt,
                        contentColor = taskColorOption(viewModel.taskColor).accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
            viewModel.reminderAt?.let { at ->
                InputChip(
                    selected = true,
                    onClick = { showDatePicker = true },
                    label = { Text("Напомнить: " + formatDateTime(at)) },
                    leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = viewModel::clearReminder) {
                            Icon(Icons.Default.Close, contentDescription = "Убрать напоминание")
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (viewModel.previewMode) {
                PreviewBlock(
                    text = viewModel.contentField.text,
                    formatting = viewModel.formatting,
                    hideCompleted = settingsState.completedStyle == CompletedStyle.HIDE,
                    onToggleCheckbox = viewModel::toggleCheckboxAtLine,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            } else {
                if (showFormattingPanel) {
                    com.notessync.app.ui.notes.text.FormattingToolbar(
                        active = viewModel.activeFormatting, advanced = settingsState.advancedFormatting,
                        onToggle = viewModel::toggleTextStyle, onFormat = viewModel::formatSelection,
                        onHeading = viewModel::toggleHeading, onList = viewModel::toggleList,
                        onAlign = { viewModel.formatParagraph(com.notessync.app.notes.text.FormatKind.ALIGN, it) },
                        onIndent = viewModel::changeIndent,
                        onChecklist = viewModel::toggleChecklist, onSelectAll = viewModel::selectAllContent)
                }
                TextField(
                    value = viewModel.title,
                    onValueChange = { viewModel.title = it },
                    placeholder = {
                        Text(
                            if (viewModel.isTask) "Название задачи" else "Заголовок",
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        // Этап 35: название задачи — цветом задачи.
                        color = if (viewModel.isTask) {
                            taskColorOption(viewModel.taskColor).accent
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    ),
                    colors = borderlessColors(),
                    modifier = Modifier.fillMaxWidth()
                )
                val visualTransformation = remember(viewModel.formatting) {
                    com.notessync.app.ui.notes.text.FormattedTextTransformation(viewModel.formatting)
                }
                var editorLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                val density = LocalDensity.current
                // Отступ текста задан явно в contentPadding DecorationBox ниже.
                val padStartPx = with(density) { 16.dp.toPx() }
                val padTopPx = with(density) { 16.dp.toPx() }
                val glyphZonePx = with(density) { 56.dp.toPx() }
                val editorInteractionSource = remember { MutableInteractionSource() }
                BasicTextField(
                    value = viewModel.contentField,
                    onValueChange = viewModel::onContentChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    // Этап 27: маркеры скрыты всегда; стили вводятся кнопками-режимами.
                    visualTransformation = visualTransformation,
                    onTextLayout = { editorLayout = it },
                    interactionSource = editorInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        // Тап по квадратику чекбокса переключает его, не двигая курсор.
                        .pointerInput(visualTransformation) {
                            awaitEachGesture {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial
                                )
                                val layout = editorLayout ?: return@awaitEachGesture
                                val source = viewModel.contentField.text
                                val position = Offset(
                                    down.position.x - padStartPx,
                                    down.position.y - padTopPx
                                )
                                val transformedOffset = layout.getOffsetForPosition(position)
                                val original = visualTransformation
                                    .filter(AnnotatedString(source))
                                    .offsetMapping.transformedToOriginal(transformedOffset)
                                val lineStart = source.lastIndexOf('\n', original - 1)
                                    .let { if (it < 0) 0 else it + 1 }
                                val lineEnd = source.indexOf('\n', original)
                                    .let { if (it < 0) source.length else it }
                                val line = source.substring(lineStart, lineEnd)
                                val isCheckbox = line.startsWith("- [ ] ") ||
                                    line.startsWith("- [x] ") || line.startsWith("- [X] ")
                                val onMarker = original in lineStart until lineStart + 6
                                if (isCheckbox && (onMarker || position.x <= glyphZonePx)) {
                                    down.consume()
                                    waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                                    val lineIndex =
                                        source.substring(0, lineStart).count { it == '\n' }
                                    viewModel.toggleCheckboxAtLine(lineIndex)
                                }
                            }
                        },
                    decorationBox = { innerTextField ->
                        TextFieldDefaults.DecorationBox(
                            value = viewModel.contentField.text,
                            innerTextField = innerTextField,
                            enabled = true,
                            singleLine = false,
                            visualTransformation = visualTransformation,
                            interactionSource = editorInteractionSource,
                            placeholder = {
                                Text(if (viewModel.isTask) "Описание задачи" else "Текст заметки")
                            },
                            colors = borderlessColors(),
                            contentPadding = PaddingValues(16.dp)
                        )
                    }
                )
            }

        }
    }

    if (showTimerResetDialog) {
        AlertDialog(
            onDismissRequest = { showTimerResetDialog = false },
            title = { Text("Сбросить таймер?") },
            text = { Text("Зафиксированное время будет удалено.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTaskTimer()
                    showTimerResetDialog = false
                }) { Text("Сбросить") }
            },
            dismissButton = {
                TextButton(onClick = { showTimerResetDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showCountdownCustomDialog) {
        var hoursText by remember { mutableStateOf("") }
        var minutesText by remember { mutableStateOf("") }
        val hours = hoursText.toLongOrNull() ?: 0L
        val minutes = minutesText.toLongOrNull() ?: 0L
        val totalMillis = (hours * 60L + minutes) * 60_000L
        AlertDialog(
            onDismissRequest = { showCountdownCustomDialog = false },
            title = { Text("Своё время") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = hoursText,
                        onValueChange = { input -> hoursText = input.filter(Char::isDigit).take(2) },
                        label = { Text("Часы") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { input -> minutesText = input.filter(Char::isDigit).take(2) },
                        label = { Text("Минуты") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.startCountdown(totalMillis)
                        showCountdownCustomDialog = false
                    },
                    enabled = totalMillis > 0L
                ) { Text("Запустить") }
            },
            dismissButton = {
                TextButton(onClick = { showCountdownCustomDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showColorDialog) {
        AlertDialog(
            onDismissRequest = { showColorDialog = false },
            title = { Text("Цвет задачи") },
            text = {
                FlowRow {
                    taskColorOptions.forEach { option ->
                        FilterChip(
                            selected = viewModel.taskColor == option.key,
                            onClick = {
                                viewModel.chooseTaskColor(option.key)
                                showColorDialog = false
                            },
                            label = { Text(option.label) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = option.accent
                                )
                            },
                            modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showColorDialog = false }) { Text("Закрыть") }
            }
        )
    }

    if (showTaskPasswordDialog) {
        SetPasswordDialog(
            title = "Пароль задачи",
            onDismiss = { showTaskPasswordDialog = false },
            onConfirm = { password ->
                viewModel.protectTask(password)
                showTaskPasswordDialog = false
            }
        )
    }

    if (showRemoveTaskProtection) {
        UnlockPasswordDialog(
            title = "Снять защиту задачи",
            showError = taskProtectionError,
            onDismiss = { showRemoveTaskProtection = false },
            onConfirm = { password ->
                viewModel.removeTaskProtection(password) { verified ->
                    if (verified) showRemoveTaskProtection = false else taskProtectionError = true
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить заметку?") },
            text = {
                Text(
                    viewModel.title.ifBlank { "Без названия" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    scope.launch {
                        val id = viewModel.deleteNow()
                        onDeleted(id)
                    }
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showTagDialog) {
        var tagName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text("Новый тег") },
            text = {
                OutlinedTextField(
                    value = tagName,
                    onValueChange = { tagName = it },
                    label = { Text("Название") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.addTag(tagName); showTagDialog = false },
                    enabled = tagName.isNotBlank()
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) { Text("Отмена") }
            }
        )
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = viewModel.reminderAt ?: System.currentTimeMillis() + 3_600_000L
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    dateState.selectedDateMillis?.let { showTimePickerFor = it }
                }) { Text("Далее") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Отмена") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    showTimePickerFor?.let { dateMillis ->
        val calendar = Calendar.getInstance().apply {
            timeInMillis = viewModel.reminderAt ?: System.currentTimeMillis() + 3_600_000L
        }
        val timeState = rememberTimePickerState(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        )
        AlertDialog(
            onDismissRequest = { showTimePickerFor = null },
            title = { Text("Время") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setReminder(
                        ReminderTime.combineDateAndTime(dateMillis, timeState.hour, timeState.minute)
                    )
                    showTimePickerFor = null
                }) { Text("Готово") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePickerFor = null }) { Text("Отмена") }
            }
        )
    }
}

/**
 * Трансформация редактора: маркеры скрыты всегда (этап 27).
 * Стили вводятся кнопками-режимами, никаких звёздочек и скобок на экране.
 */
@Composable
private fun editorTransformation(): MarkdownVisualTransformation =
    remember { MarkdownVisualTransformation() }

/** Предпросмотр: markdown отрисован стилями; тап по чек-боксу переключает его. */
@Composable
private fun PreviewBlock(
    text: String,
    formatting: com.notessync.app.notes.text.TextFormatting,
    hideCompleted: Boolean,
    onToggleCheckbox: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val parsed = remember(text, formatting, hideCompleted) { com.notessync.app.ui.notes.text.formattedPreview(text, formatting, hideCompleted) }
    // Соответствие видимых строк строкам исходника (при скрытии выполненных).
    val sourceLines = remember(text, hideCompleted) {
        MarkdownLite.visibleSourceLines(text, hideCompleted)
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    ClickableText(
        text = parsed,
        style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        onTextLayout = { layout = it },
        onClick = { offset ->
            layout?.let { result ->
                val visibleLine = result.getLineForOffset(offset)
                val logicalLine = parsed.text.substring(0, result.getLineStart(visibleLine)).count { it == '\n' }
                val sourceLine = parsed.getStringAnnotations(com.notessync.app.ui.notes.text.SOURCE_LINE_ANNOTATION, offset, offset)
                    .lastOrNull()?.item?.toIntOrNull() ?: sourceLines.getOrElse(logicalLine) { logicalLine }
                onToggleCheckbox(sourceLine)
            }
        },
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    )
}

/** Шаблоны обратного отсчёта (Этап 30). */
private val countdownPresets: List<Pair<String, Long>> = listOf(
    "10 минут" to 10 * 60_000L,
    "15 минут" to 15 * 60_000L,
    "30 минут" to 30 * 60_000L,
    "1 час" to 60 * 60_000L,
    "2 часа" to 2 * 60 * 60_000L
)

private fun openNotificationSettings(context: android.content.Context) {
    val notificationSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching { context.startActivity(notificationSettings) }.onFailure {
        val appSettings = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
        runCatching { context.startActivity(appSettings) }
    }
}

private fun openAttachment(context: android.content.Context, path: String, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "com.notessync.app.fileprovider", File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}

@Composable
private fun borderlessColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)

