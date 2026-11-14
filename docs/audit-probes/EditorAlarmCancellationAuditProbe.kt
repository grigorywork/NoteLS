package com.notessync.app.ui.notes.audit

import android.app.AlarmManager
import android.app.Application
import com.notessync.app.reminders.*
import com.notessync.app.ui.notes.EditorTaskTiming
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Controlled suspension at the real title-provider boundary; no sleeps/phone timing assumptions. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class EditorAlarmCancellationAuditProbe {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val alarms get() = app.getSystemService(AlarmManager::class.java)
    @Before fun before() { app.createReminderChannel(); assertTrue(ReminderScheduler.canNotify(app)) }
    @After fun after() {
        scope.cancel(); dispatcher.scheduler.runCurrent()
        shadowOf(alarms).scheduledAlarms.toList().forEach { it.operation?.let { operation -> alarms.cancel(operation) } }
    }
    private fun timing(title: suspend () -> String) = EditorTaskTiming(app, scope,
        { true }, { false }, { false }, { 123L }, title, persist = {}, report = {})
    @Test fun `clearing reminder while title is loading cannot rearm the cleared alarm`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val timing = timing { entered.complete(Unit); release.await(); "Fixture" }
        timing.setReminder(System.currentTimeMillis() + 3600000); dispatcher.scheduler.runCurrent()
        assertTrue(entered.isCompleted)
        timing.clearReminder(); dispatcher.scheduler.runCurrent()
        assertNull(timing.reminderAt); assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertTrue("A stale set must not rearm a cleared reminder", shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun `cancelling countdown while title is loading cannot rearm the cancelled timer`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val timing = timing { entered.complete(Unit); release.await(); "Fixture" }
        timing.startCountdown(3600000); dispatcher.scheduler.runCurrent()
        assertTrue(entered.isCompleted)
        timing.cancelCountdown(); dispatcher.scheduler.runCurrent()
        assertNull(timing.taskCountdownEndAt); assertTrue(shadowOf(alarms).scheduledAlarms.isEmpty())
        release.complete(Unit); dispatcher.scheduler.runCurrent()
        assertTrue("A stale start must not rearm a cancelled timer", shadowOf(alarms).scheduledAlarms.isEmpty())
    }
    @Test fun `older reminder completion cannot replace a later chosen target`() = runTest(dispatcher) {
        val oldReady = CompletableDeferred<Unit>(); val releaseOld = CompletableDeferred<Unit>(); var calls = 0
        val timing = timing { if (++calls == 1) { oldReady.complete(Unit); releaseOld.await() }; "Fixture" }
        val first = System.currentTimeMillis() + 3600000; val second = first + 3600000
        timing.setReminder(first); dispatcher.scheduler.runCurrent(); assertTrue(oldReady.isCompleted)
        timing.setReminder(second); dispatcher.scheduler.runCurrent()
        assertEquals(second, shadowOf(alarms).scheduledAlarms.single().triggerAtMs)
        releaseOld.complete(Unit); dispatcher.scheduler.runCurrent()
        assertEquals("The newer chosen time must win", second, shadowOf(alarms).scheduledAlarms.single().triggerAtMs)
    }
}
