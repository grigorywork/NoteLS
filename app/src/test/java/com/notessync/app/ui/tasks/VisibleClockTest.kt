package com.notessync.app.ui.tasks

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class VisibleClockTest {
    private class Owner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this)
    }

    @Test fun `clock stops in background resumes immediately and ends on destroy`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            var ticks = 0
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            val job = launch { owner.lifecycle.runVisibleClock({ true }) { ticks++ } }
            runCurrent()
            advanceTimeBy(5_000); runCurrent()
            assertEquals(0, ticks)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(1, ticks)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(3, ticks)
            owner.lifecycle.currentState = Lifecycle.State.CREATED
            runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(3, ticks)
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertEquals(4, ticks)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(4, ticks)
            job.join()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `finished countdown stops ticking while screen remains open`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val owner = Owner()
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            var ticks = 0
            val job = launch { owner.lifecycle.runVisibleClock({ ticks < 2 }) { ticks++ } }
            runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(2, ticks)
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            runCurrent()
            job.join()
        } finally { Dispatchers.resetMain() }
    }
}
