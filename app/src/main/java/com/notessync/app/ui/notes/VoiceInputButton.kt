package com.notessync.app.ui.notes

import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.VoiceControl
import com.notessync.app.voice.VoiceState

/** Initial-pass consumption keeps the IconButton click from starting a second touch session. */
@Composable
internal fun VoiceInputButton(control: VoiceControl, state: VoiceState,
    onStart: () -> Unit, onFinish: () -> Unit, onCancel: () -> Unit, enabled: Boolean = true) {
    val currentState by rememberUpdatedState(state)
    val start by rememberUpdatedState(onStart)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    val hold = if (control == VoiceControl.HOLD && enabled) Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
            val finishOnRelease = currentState != VoiceState.PROCESSING
            if (currentState == VoiceState.IDLE) start()
            else if (!finishOnRelease) cancel()
            var released = false
            try {
                val up = waitForUpOrCancellation(PointerEventPass.Initial)
                released = up != null
                up?.consume()
            } finally {
                if (finishOnRelease) { if (released) finish() else cancel() }
            }
        }
    } else Modifier
    IconButton(
        // Accessibility and keyboard click can toggle without a sustained physical press.
        onClick = { when (currentState) {
            VoiceState.IDLE -> start()
            VoiceState.PROCESSING -> cancel()
            else -> finish()
        } }, enabled = enabled, modifier = Modifier.testTag("voice-input").then(hold)
    ) {
        Icon(when (state) { VoiceState.IDLE -> Icons.Default.Mic; VoiceState.PROCESSING -> Icons.Default.Close; else -> Icons.Default.Stop },
            contentDescription = when (state) {
                VoiceState.IDLE -> if (control == VoiceControl.HOLD) uiText("Диктовка: удерживайте микрофон") else uiText("Начать диктовку")
                VoiceState.PROCESSING -> uiText("Отменить распознавание")
                else -> uiText("Завершить диктовку")
            }, modifier = Modifier.size(20.dp), tint = if (state == VoiceState.IDLE) LocalContentColor.current else MaterialTheme.colorScheme.error)
    }
}
