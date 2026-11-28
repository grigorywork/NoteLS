package com.notessync.app.ui.screens

import com.notessync.app.ui.editor.*
import com.notessync.app.i18n.uiText
import com.notessync.app.audio.*
import com.notessync.app.ui.notes.audio.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.focus.FocusRequester
import com.notessync.app.notes.history.EditorField
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notessync.app.reminders.ReminderScheduler
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.settings.FormattingBarPosition
import com.notessync.app.ui.notes.EditorMessage
import com.notessync.app.ui.notes.EditorScaffold
import com.notessync.app.ui.notes.NoteEditorViewModel
import com.notessync.app.ui.notes.PhotoAttachmentGrid
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
    onBack: () -> Unit,
    onDeleted: (com.notessync.app.data.local.model.NoteIdentity) -> Unit,
    viewModel: NoteEditorViewModel = viewModel(),
    onOpenCalendar: () -> Unit = {},
    imeInsets: WindowInsets = WindowInsets.ime,
    onOpenTransformSettings: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val ui = remember(viewModel) { EditorUiState() }
    val selectionToolbar = ui.selectionToolbar
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val titleFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    LaunchedEffect(viewModel.historyRestoreVersion, viewModel.previewMode, ui.openTable) {
        if (viewModel.historyRestoreVersion > 0 && !viewModel.previewMode && ui.openTable == null && viewModel.editorField != EditorField.TABLE) {
            if (viewModel.editorField == EditorField.TITLE) titleFocus.requestFocus() else contentFocus.requestFocus()
        }
    }

    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()
    val captureState by viewModel.audioCapture.state.collectAsStateWithLifecycle()
    val audioPlaying by remember(viewModel) { viewModel.audioPlayback.state.map { it.active }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = false)
    val photos = remember(attachments) { attachments.filterNot { it.mimeType.startsWith("audio/", ignoreCase = true) } }
    val audios = remember(attachments) { attachments.filter { it.mimeType.startsWith("audio/", ignoreCase = true) } }

    val settingsState by remember { SettingsRepository.get(context).state }.collectAsStateWithLifecycle()

    // Этап 32: голосовой ввод.
    var voiceState by remember(viewModel) { mutableStateOf(com.notessync.app.voice.VoiceState.IDLE) }
    var voiceClosing by remember(viewModel) { mutableStateOf(false) }
    var focusAfterVoice by remember(viewModel) { mutableStateOf(false) }
    var voicePartial by remember(viewModel) { mutableStateOf("") }
    var voiceStructureAtStart by remember(viewModel) { mutableStateOf(settingsState.voiceStructure) }
    var permissionPending by remember(viewModel) { mutableStateOf(false) }
    val currentVoiceEngine = rememberUpdatedState(settingsState.voiceEngine)
    val currentVoiceControl = rememberUpdatedState(settingsState.voiceControl)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val currentVoiceLanguage = rememberUpdatedState(settingsState.voiceLanguage)
    val voiceManager = remember(viewModel, context, lifecycle) {
        com.notessync.app.voice.VoiceInputManager(context,
            object : com.notessync.app.voice.VoiceInputManager.Listener {
                override fun onState(state: com.notessync.app.voice.VoiceState) {
                    voiceState = state
                    if (state == com.notessync.app.voice.VoiceState.IDLE) {
                        voicePartial = ""
                        if (focusAfterVoice) scope.launch {
                            if (ui.showVoice && !voiceClosing && !viewModel.accessDenied && !viewModel.previewMode &&
                                !viewModel.isClosing && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) contentFocus.requestFocus()
                        }
                        focusAfterVoice = false
                    }
                }
                override fun onPartial(text: String) { voicePartial = text }
                override fun onFinal(text: String) {
                    if (!voiceClosing && !ui.modalOpen && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        viewModel.insertVoiceText(text, voiceStructureAtStart)
                        // A confirmed phrase is not the end of a hold/toggle gesture.
                        // Keep the same panel and pointer target; do not move focus mid-dictation.
                    }
                }
                override fun onError(message: String) {
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            }, languageTag = { currentVoiceLanguage.value.languageTag() })
    }
    DisposableEffect(voiceManager) { onDispose {
        voiceManager.cancel(); viewModel.audioCapture.finish(); viewModel.audioPlayback.stop()
    } }
    LaunchedEffect(settingsState.audioRecording) { if (!settingsState.audioRecording) viewModel.audioCapture.finish() }
    LaunchedEffect(captureState.token, captureState.message) {
        captureState.message?.let {
            snackbarHostState.showSnackbar(captureMessage(it))
            viewModel.audioCapture.clearMessage(captureState.token)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.audioCapture.finish()
        viewModel.audioPlayback.stop()
        voiceManager.cancel()
        voicePartial = ""
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionPending = false
        scope.launch {
            snackbarHostState.showSnackbar(if (!granted) uiText("Без разрешения на микрофон голосовой ввод недоступен")
                else if (currentVoiceControl.value == com.notessync.app.settings.VoiceControl.HOLD)
                    uiText("Разрешение получено. Снова удерживайте микрофон для диктовки.")
                else uiText("Разрешение получено. Нажмите микрофон ещё раз."))
        }
    }
    fun finishVoiceInput() {
        focusAfterVoice = true
        voiceManager.finish()
        if (voiceState == com.notessync.app.voice.VoiceState.IDLE) focusAfterVoice = false
    }
    fun startVoiceInput() {
        if (!viewModel.isReady || viewModel.isClosing || viewModel.accessDenied || viewModel.previewMode || voiceClosing || captureState.busy || audioPlaying) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || permissionPending) return
        val engine = currentVoiceEngine.value
        if (!voiceManager.isAvailable(engine)) {
            scope.launch { snackbarHostState.showSnackbar(voiceManager.unavailableMessage(engine)) }
            return
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceStructureAtStart = settingsState.voiceStructure
            voiceManager.start(engine)
        } else {
            permissionPending = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(viewModel.previewMode, viewModel.accessDenied, viewModel.isClosing, ui.modalOpen) {
        if (viewModel.previewMode || viewModel.accessDenied || viewModel.isClosing || ui.modalOpen) voiceManager.cancel()
    }

    var audioPermissionPending by remember { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        audioPermissionPending = false
        scope.launch { snackbarHostState.showSnackbar(if (granted) uiText("Разрешение получено. Снова выберите запись аудио.")
            else uiText("Без разрешения на микрофон запись аудио недоступна.")) }
    }
    fun startAudioRecording() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || voiceClosing || permissionPending || audioPermissionPending || captureState.busy) return
        voiceManager.cancel()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.startAudioRecording()
        else { audioPermissionPending = true; audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::attach) }
    val cameraLauncher = rememberLauncherForActivityResult(
        com.notessync.app.ui.notes.photos.CameraCaptureContract()
    ) { viewModel.finishCameraCapture(it) }
    fun startCamera() {
        scope.launch {
            val uri = viewModel.prepareCameraCapture() ?: return@launch
            try { cameraLauncher.launch(uri) }
            catch (_: Exception) {
                viewModel.finishCameraCapture(false)
                snackbarHostState.showSnackbar(uiText("Не удалось открыть камеру. Выберите фото из галереи."))
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera()
        else scope.launch { snackbarHostState.showSnackbar(uiText("Для съёмки разрешите доступ к камере в настройках приложения.")) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && ReminderScheduler.canNotify(context)) {
            ui.showReminderMenu = true
        } else {
            viewModel.reportReminderNotificationsDisabled()
        }
    }

    fun openReminderMenu() {
        when {
            ReminderScheduler.canNotify(context) -> ui.showReminderMenu = true
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
            EditorMessage.SUBTASK_LIMIT -> uiText("Достигнут предел подзадач или их текста.")
            EditorMessage.TABLE_LIMIT -> uiText("Достигнут предел таблиц или текста ячейки.")
            EditorMessage.SAVE_FAILED -> uiText("Не удалось сохранить заметку. Повторите попытку.")
            EditorMessage.HISTORY_LIMIT -> uiText("Изменение слишком велико для истории. Текст сохранён в редакторе.")
            EditorMessage.FORMATTING_LIMIT -> uiText("Достигнут предел оформления. Текст сохранён; используйте более крупные выделения.")
            EditorMessage.CAMERA_FAILED -> uiText("Не удалось подготовить фото с камеры. Попробуйте ещё раз.")
            EditorMessage.PHOTO_TOO_LARGE -> uiText("Фото больше 10 МБ. Выберите изображение меньшего размера.")
            EditorMessage.PHOTO_SIZE_FAILED -> uiText("Не удалось сохранить размер фото. Повторите попытку.")
            EditorMessage.PHOTO_IMPORT_FAILED -> uiText("Не удалось прикрепить фото. Попробуйте другое изображение.")
            EditorMessage.REMINDER_IN_PAST -> uiText("Выберите время напоминания в будущем.")
            EditorMessage.REMINDER_NOTIFICATIONS_DISABLED ->
                uiText("Уведомления выключены. Разрешите их, чтобы видеть напоминания.")
            EditorMessage.REMINDER_SCHEDULE_FAILED ->
                uiText("Не удалось установить напоминание. Попробуйте ещё раз.")
        }
        val notificationsDisabled = current == EditorMessage.REMINDER_NOTIFICATIONS_DISABLED
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = if (notificationsDisabled) uiText("Настройки") else null,
            duration = if (notificationsDisabled) SnackbarDuration.Long else SnackbarDuration.Short
        )
        if (notificationsDisabled && result == SnackbarResult.ActionPerformed) {
            openNotificationSettings(context)
        }
        viewModel.clearMessage()
    }

    fun exit() {
        voiceManager.cancel()
        val audioSaved = viewModel.audioCapture.finish()
        viewModel.audioPlayback.stop()
        if (viewModel.isClosing || viewModel.isPhotoBusy) return
        voiceClosing = true
        scope.launch {
            if (!audioSaved.await()) { voiceClosing = false; return@launch }
            // Сначала принудительное сохранение (снимает гонку автосохранения),
            // потом удаление пустой черновой заметки.
            if (viewModel.flushOnExit()) onBack() else voiceClosing = false
        }
    }
    fun deleteNote() {
        ui.showDeleteDialog = false
        voiceClosing = true
        voiceManager.cancel()
        val discardedAudio = viewModel.audioCapture.cancel()
        viewModel.audioPlayback.stop()
        scope.launch {
            try {
                discardedAudio.await()
                val id = viewModel.deleteNow()
                onDeleted(viewModel.noteIdentity.copy(id = id))
            } finally { voiceClosing = false }
        }
    }
    BackHandler {
        if (ui.popup != null) ui.dismissMenus()
        else if (selectionToolbar.status == androidx.compose.ui.platform.TextToolbarStatus.Shown) selectionToolbar.hide()
        else if (ui.showVoice) { voiceManager.cancel(); ui.showVoice = false }
        else exit()
    }
    LaunchedEffect(ui.popup) {
        if (ui.popup != null && ui.formatPopup?.anchor != com.notessync.app.ui.notes.text.FormatAnchor.CONTEXT) selectionToolbar.hide()
    }
    LaunchedEffect(ui.modalOpen) { selectionToolbar.hide() }
    val historyKeys = Modifier.onPreviewKeyEvent {
        if (!viewModel.historyEnabled || !it.isCtrlPressed || it.type != KeyEventType.KeyDown) false
        else when (it.key) {
            Key.Z -> { if (it.isShiftPressed) viewModel.redo() else viewModel.undo(); true }
            Key.Y -> { viewModel.redo(); true }
            Key.V, Key.X -> { viewModel.prepareClipboardEdit(); false }
            else -> false
        }
    }


    if (ui.showTransform && settingsState.aiEnabled && !viewModel.accessDenied) {
        com.notessync.app.ai.EditorTransformDialog(viewModel, SettingsRepository.get(context), onOpenSettings = {
            scope.launch { if (viewModel.saveNow(force = true)) onOpenTransformSettings() }
        }) { ui.showTransform = false }
    }
    // Читаем выделение внутри самих topBar/bottomBar: иначе каждый ввод символа
    // пересобирал бы весь экран заметки (важно после этапа 78).
    fun formattingAllowed(): Boolean = !ui.showVoice && (viewModel.contentField.selection.collapsed ||
        (selectionToolbar.status != androidx.compose.ui.platform.TextToolbarStatus.Shown &&
            ui.formatPopup?.anchor != com.notessync.app.ui.notes.text.FormatAnchor.CONTEXT))
    fun voiceIdle(): Boolean = voiceState == com.notessync.app.voice.VoiceState.IDLE
    val formattingOnTop = settingsState.formattingBarPosition == FormattingBarPosition.TOP
    val onRecordAudioAction: () -> Unit = { startAudioRecording() }
    val onGalleryAction: () -> Unit = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val onCameraAction: () -> Unit = {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }
    val onReminderAction: () -> Unit = { openReminderMenu() }
    EditorScaffold(
        imeInsets = imeInsets,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                val voiceEnabled = viewModel.isReady && !viewModel.isClosing && !viewModel.accessDenied &&
                    !viewModel.previewMode && !voiceClosing && !captureState.busy && !audioPlaying
                EditorTopBar(viewModel, ui, settingsState, onExit = { exit() }, onCalendar = {
                    voiceManager.cancel()
                    scope.launch { if (viewModel.saveNow(force = true)) onOpenCalendar() }
                }, voiceEnabled = voiceEnabled, onVoice = { ui.showVoice = true },
                    onFormattingPanelChange = { SettingsRepository.get(context).setFormattingBarPosition(it) },
                    transformIdle = voiceIdle() && !voiceClosing && !captureState.busy && !audioPlaying)
                if (ui.showVoice) EditorVoicePanel(ui, settingsState, SettingsRepository.get(context), voiceState,
                    voicePartial, voiceEnabled, onStart = { startVoiceInput() }, onFinish = { finishVoiceInput() },
                    onCancel = voiceManager::cancel, onClose = { voiceManager.cancel(); ui.showVoice = false })
                if (formattingOnTop) EditorFormattingPanel(viewModel, ui, settingsState, notebooks, "formatting-panel-top",
                    formattingAllowed(), captureState.busy, voiceClosing, onRecordAudioAction, onGalleryAction,
                    onCameraAction, onReminderAction, voiceIdle())
            }
        },
        bottomBar = {
            EditorBottomBar(viewModel, ui, settingsState, notebooks, captureState.busy, voiceClosing,
                onRecordAudio = onRecordAudioAction, onGallery = onGalleryAction, onCamera = onCameraAction,
                onReminder = onReminderAction, voiceIdle = voiceIdle(),
                showFormatting = formattingAllowed(), panelOnTop = formattingOnTop)
        }
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val inputMinHeight = (viewportHeight - 120.dp).coerceAtLeast(160.dp)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (!ui.showMedia) {
            AudioRecordingPanel(viewModel.audioCapture, captureState)
            AudioAttachmentList(audios, viewModel.audioPlayback, viewModel::removeAttachment) { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
            if (photos.isNotEmpty()) {
                PhotoAttachmentGrid(
                    attachments = photos,
                    onOpen = { ui.openPhoto = it.id },
                    onRemove = viewModel::removeAttachment
                )
            }
            }
            EditorTaskControls(viewModel, ui, settingsState)

            EditorTextFields(viewModel, ui, settingsState, historyKeys, titleFocus, contentFocus, captureState.busy, voiceState, voiceClosing,
                inputMinHeight, viewportHeight, selectionToolbar)
            EditorSubtasks(viewModel, settingsState)

        }
        }
    }

    EditorSelectionMenu(selectionToolbar, viewModel, ui, settingsState)
    if (ui.showMedia) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { ui.showMedia = false }) {
        Column(Modifier.fillMaxWidth()) {
            AudioRecordingPanel(viewModel.audioCapture, captureState)
            if (photos.isEmpty() && audios.isEmpty()) Text(uiText("Нет вложений"), Modifier.padding(16.dp))
            PhotoAttachmentGrid(photos, onOpen = { ui.showMedia = false; ui.openPhoto = it.id }, onRemove = viewModel::removeAttachment)
            AudioAttachmentList(audios, viewModel.audioPlayback, viewModel::removeAttachment) { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }
    EditorDialogs(viewModel, ui, settingsState, attachments, snackbarHostState, scope, onDelete = { deleteNote() })
}

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
