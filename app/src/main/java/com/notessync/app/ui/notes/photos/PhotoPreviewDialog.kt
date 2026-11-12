package com.notessync.app.ui.notes.photos

import com.notessync.app.i18n.uiText

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.notessync.app.data.local.entity.AttachmentEntity
import com.notessync.app.notes.photos.*
import com.notessync.app.ui.notes.EditorScaffold
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoPreviewDialog(photo: AttachmentEntity, resizeEnabled: Boolean, snackbar: SnackbarHostState,
    onSave: suspend (PhotoDisplaySize) -> Boolean, onOriginal: () -> Unit, onDismiss: () -> Unit) {
    val baselineWidth = rememberSaveable(photo.id) { photo.displayWidthDp }
    val baselineHeight = rememberSaveable(photo.id) { photo.displayHeightDp }
    val initial = PhotoDisplaySize(baselineWidth, baselineHeight).bounded()
    var width by rememberSaveable(photo.id) { mutableIntStateOf(initial.width) }
    var height by rememberSaveable(photo.id) { mutableIntStateOf(initial.height) }
    val size = PhotoDisplaySize(width, height)
    fun changeSize(value: PhotoDisplaySize) { val bounded = value.bounded(); width = bounded.width; height = bounded.height }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val image = rememberPhotoBitmap(photo, 1024)
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxSize()) {
            EditorScaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                TopAppBar(title = { Text(uiText("Фото")) }, navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, uiText("Закрыть фото")) }
                }, actions = { TextButton(onClick = onOriginal, enabled = !busy) { Text(uiText("Оригинал")) } })
            }, bottomBar = {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.End) {
                    if (resizeEnabled) TextButton(onClick = { changeSize(PhotoDisplaySize()) }, enabled = !busy) { Text(uiText("Сбросить")) }
                    TextButton(onClick = {
                        scope.launch {
                            busy = true
                            try { if (!resizeEnabled || size == initial || onSave(size)) onDismiss() } finally { busy = false }
                        }
                    }, enabled = !busy) { Text(uiText("Готово")) }
                }
            }) {
                Column(Modifier.fillMaxSize()) {
                    if (resizeEnabled) PhotoSizeControls(size, ::changeSize, !busy)
                    if (image is PhotoBitmapState.Unavailable) Text(uiText("Не удалось загрузить фото"), Modifier.padding(16.dp))
                    PhotoFrame(size, resizeEnabled && !busy, ::changeSize, Modifier.fillMaxWidth().weight(1f)) {
                        if (image is PhotoBitmapState.Ready) Image(image.bitmap.asImageBitmap(), uiText("Предпросмотр фото"),
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        else Icon(Icons.Default.Image, uiText("Предпросмотр фото"), Modifier.fillMaxSize().padding(48.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoSizeControls(size: PhotoDisplaySize, onSize: (PhotoDisplaySize) -> Unit, enabled: Boolean) {
    FlowRow(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(uiText("Ширина %1\$s%", (size.width * 100 / PhotoDisplaySize.DEFAULT_WIDTH)), Modifier.align(Alignment.CenterVertically))
        IconButton({ onSize(size.copy(width = size.width - 24).bounded()) }, enabled = enabled && size.width > PhotoDisplaySize.MIN) {
            Icon(Icons.Default.Remove, uiText("Уменьшить ширину")) }
        IconButton({ onSize(size.copy(width = size.width + 24).bounded()) }, enabled = enabled && size.width < PhotoDisplaySize.MAX) {
            Icon(Icons.Default.Add, uiText("Увеличить ширину")) }
        Text(uiText("Высота %1\$s%", (size.height * 100 / PhotoDisplaySize.DEFAULT_HEIGHT)), Modifier.align(Alignment.CenterVertically))
        IconButton({ onSize(size.copy(height = size.height - 18).bounded()) }, enabled = enabled && size.height > PhotoDisplaySize.MIN) {
            Icon(Icons.Default.Remove, uiText("Уменьшить высоту")) }
        IconButton({ onSize(size.copy(height = size.height + 18).bounded()) }, enabled = enabled && size.height < PhotoDisplaySize.MAX) {
            Icon(Icons.Default.Add, uiText("Увеличить высоту")) }
    }
}

/** Draft geometry; persistence is an explicit action in the dialog, never part of pointer frames. */
@Composable
internal fun PhotoFrame(size: PhotoDisplaySize, resizeEnabled: Boolean, onSize: (PhotoDisplaySize) -> Unit,
    modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current.density
    val latestSize by rememberUpdatedState(size)
    Box(modifier.horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState())) {
        Box(Modifier.padding(24.dp).size(size.width.dp, size.height.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline).testTag("photo-frame")) {
            content()
            if (resizeEnabled) PhotoHandle.entries.forEach { handle ->
                val alignment = when (handle) {
                    PhotoHandle.TOP_LEFT -> Alignment.TopStart; PhotoHandle.TOP -> Alignment.TopCenter
                    PhotoHandle.TOP_RIGHT -> Alignment.TopEnd; PhotoHandle.LEFT -> Alignment.CenterStart
                    PhotoHandle.RIGHT -> Alignment.CenterEnd; PhotoHandle.BOTTOM_LEFT -> Alignment.BottomStart
                    PhotoHandle.BOTTOM -> Alignment.BottomCenter; PhotoHandle.BOTTOM_RIGHT -> Alignment.BottomEnd
                }
                var start by remember { mutableStateOf(size) }
                var dx by remember { mutableFloatStateOf(0f) }; var dy by remember { mutableFloatStateOf(0f) }
                Box(Modifier.align(alignment).offset((handle.x * 24).dp, (handle.y * 24).dp)
                    .size(48.dp).testTag("photo-handle-${handle.name}")
                    .semantics { contentDescription = handle.label }
                    .pointerInput(handle, resizeEnabled, density) {
                        detectDragGestures(onDragStart = { start = latestSize; dx = 0f; dy = 0f },
                            onDrag = { change, drag ->
                                change.consume(); dx += drag.x / density; dy += drag.y / density
                                onSize(start.drag(handle, dx.roundToInt(), dy.roundToInt()))
                            }, onDragCancel = { onSize(start) })
                    }, contentAlignment = Alignment.Center) {
                    Surface(Modifier.size(24.dp), shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer) {
                        Icon(Icons.Default.DragIndicator, null, Modifier.padding(2.dp))
                    }
                }
            }
        }
    }
}
