package com.notessync.app.ui.analytics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.notessync.app.ui.tasks.TaskDeadlines
import kotlinx.coroutines.delay
import java.time.ZoneId

/** One nearest-deadline/midnight wake-up while foreground; no worker, polling or background receiver. */
@Composable
internal fun AnalyticsClock(viewModel: AnalyticsViewModel, state: AnalyticsState) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val zone = ZoneId.systemDefault()
    LifecycleStartEffect(viewModel, context) {
        viewModel.refresh()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { viewModel.refresh() }
        }
        val events = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, events, ContextCompat.RECEIVER_EXPORTED)
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(lifecycle, state.loading, state.capturedAt, state.nextClockBoundary, zone.id) {
        if (state.loading) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val now = viewModel.timeSource()
            val target = TaskDeadlines.nextRefresh(now, state.nextClockBoundary, zone, capturedAt = state.capturedAt)
            delay((target - now).coerceAtLeast(1))
            viewModel.refresh()
        }
    }
}
