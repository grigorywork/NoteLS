package com.notessync.app.ui.tasks

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** UI refresh only. Elapsed time and alarms remain based on absolute timestamps. */
internal suspend fun Lifecycle.runVisibleClock(keepTicking: () -> Boolean, tick: () -> Unit) {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (keepTicking()) {
            tick()
            delay(1_000L)
        }
    }
}
