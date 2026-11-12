package com.notessync.app.ui.tasks

import com.notessync.app.i18n.uiText

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** Visible running stopwatch; epoch timestamps remain independent of UI refresh. */
@Composable
fun TaskTimerIndicator(
    elapsedMillis: Long,
    startedAt: Long?,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    animateIcon: Boolean = false,
    active: Boolean = true
) {
    var now by remember(startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val running = startedAt != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(startedAt, lifecycle, active) {
        if (startedAt != null && active) lifecycle.runVisibleClock({ true }) {
            now = System.currentTimeMillis()
        }
    }
    val lifecycleState by lifecycle.currentStateAsState()
    val motion = animateIcon && active && running && lifecycleState.isAtLeast(Lifecycle.State.STARTED) && android.animation.ValueAnimator.areAnimatorsEnabled()
    val angle = if (motion) {
        val transition = rememberInfiniteTransition(label = "stopwatch")
        transition.animateFloat(0f, 360f,
            infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "stopwatch-hand")
    } else remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val color = if (contentColor == Color.Unspecified) LocalContentColor.current else contentColor
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Schedule,
            contentDescription = if (running) uiText("Секундомер работает") else uiText("Секундомер на паузе"),
            tint = color,
            modifier = Modifier.graphicsLayer { rotationZ = angle.value }
        )
        Text(
            text = TaskTimer.format(TaskTimer.elapsedAt(elapsedMillis, startedAt, now)),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/**
 * Обратный отсчёт — песочные часы (Этап 35).
 * Цикл замедлен втрое и разбит на фазы: часы стоят — «песок сыплется» (3.6 с),
 * затем плавный переворот на 180° (1.2 с), снова пауза и переворот до 360°.
 * На паузе и по финишу — неподвижные часы.
 */
@Composable
fun CountdownIndicator(
    countdownMillis: Long,
    endAt: Long?,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    animateIcon: Boolean = true,
    active: Boolean = true
) {
    var now by remember(endAt) { mutableLongStateOf(System.currentTimeMillis()) }
    val running = endAt != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Cards need one-second text updates, not a frame-by-frame animation observer.
    val canAnimate = if (animateIcon && active && android.animation.ValueAnimator.areAnimatorsEnabled()) {
        val lifecycleState by lifecycle.currentStateAsState()
        lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    } else false
    LaunchedEffect(endAt, lifecycle, active) {
        if (endAt != null && active) lifecycle.runVisibleClock(
            keepTicking = { !TaskTimer.countdownFinished(countdownMillis, endAt, now) }
        ) { now = System.currentTimeMillis() }
    }
    val remaining = TaskTimer.countdownRemaining(countdownMillis, endAt, now)
    val finished = TaskTimer.countdownFinished(countdownMillis, endAt, now)
    val rotation = if (canAnimate && running && !finished) {
        val transition = rememberInfiniteTransition(label = "countdownRotation")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 9_600
                    0f at 0
                    0f at 3_600 // стоим: песок сыплется
                    180f at 4_800 using FastOutSlowInEasing // плавный переворот
                    180f at 8_400 // снова стоим: песок сыплется
                    360f at 9_600 using FastOutSlowInEasing
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "countdownAngle"
        )
    } else {
        remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    }
    val color = when {
        finished -> MaterialTheme.colorScheme.error
        contentColor != Color.Unspecified -> contentColor
        else -> LocalContentColor.current
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.HourglassEmpty,
            contentDescription = when {
                finished -> uiText("Время вышло")
                running -> uiText("Таймер идёт")
                else -> uiText("Таймер на паузе")
            },
            tint = color,
            modifier = if (animateIcon) Modifier.graphicsLayer { rotationZ = rotation.value } else Modifier
        )
        Text(
            text = if (finished) uiText("Время вышло") else TaskTimer.format(remaining),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

