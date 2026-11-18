package com.notessync.app.voice

import com.notessync.app.settings.VoiceEngine
import org.junit.Assert.*
import org.junit.Test

class VoiceContinuityRegressionTest {
    private class Clock : VoiceDeadline {
        data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        val tasks = mutableListOf<Task>()
        override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delayMillis, action); tasks += task
            return { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= until }.minByOrNull { it.at } ?: break
                now = task.at; task.cancelled = true; task.action()
            }
            now = until
        }
        fun pending() = tasks.count { !it.cancelled }
    }
    private class Native(val callbacks: VoiceCallbacks) : VoiceRecognizer {
        var starts = 0; var stops = 0; var cancels = 0; var destroys = 0
        override fun start() { starts++ }
        override fun finish() { stops++ }
        override fun cancel() { cancels++ }
        override fun destroy() { destroys++ }
    }
    private val clock = Clock()
    private val created = mutableListOf<Native>()
    private val engines = mutableListOf<VoiceEngine>()
    private val finals = mutableListOf<String>()
    private val errors = mutableListOf<VoiceFailure>()
    private val states = mutableListOf<VoiceState>()
    private val factory = object : VoiceRecognizerFactory {
        override fun available(engine: VoiceEngine) = true
        override fun create(engine: VoiceEngine, callbacks: VoiceCallbacks): VoiceRecognizer {
            engines += engine
            return Native(callbacks).also { created += it }
        }
    }
    private val session = VoiceSession(factory, clock, object : VoiceSession.Listener {
        override fun state(state: VoiceState) { states += state }
        override fun partial(text: String) {}
        override fun result(text: String) { finals += text }
        override fun error(failure: VoiceFailure) { errors += failure }
    }, uptime = { clock.now })
    private val current get() = created.last()
    private fun start(engine: VoiceEngine = VoiceEngine.ONLINE) { session.start(engine); current.callbacks.ready() }
    private fun phrase(text: String) {
        current.callbacks.endOfSpeech(); current.callbacks.result(text)
        assertEquals(VoiceState.RESTARTING, session.state)
        clock.advance(250); current.callbacks.ready()
    }

    @Test fun `provider endpoint commits once and rearms same engine without an idle or processing reset`() {
        start(VoiceEngine.LOCAL); val first = current
        phrase("First phrase"); phrase("Second phrase")
        first.callbacks.result("Duplicate"); first.callbacks.error(VoiceFailure.CLIENT)
        assertEquals(listOf("First phrase", "Second phrase"), finals)
        assertEquals(listOf(VoiceEngine.LOCAL, VoiceEngine.LOCAL, VoiceEngine.LOCAL), engines)
        assertFalse(states.contains(VoiceState.IDLE)); assertFalse(states.contains(VoiceState.PROCESSING))
        assertEquals(VoiceState.LISTENING, session.state)
        assertTrue(created.dropLast(1).all { it.destroys == 1 }); session.cancel(); assertEquals(0, clock.pending())
    }

    @Test fun `repeated five second silent pauses remain active beyond the previous ninety second session cutoff`() {
        start()
        repeat(40) {
            clock.advance(5_000); current.callbacks.error(VoiceFailure.SILENCE)
            assertEquals(VoiceState.RESTARTING, session.state)
            clock.advance(250); current.callbacks.ready()
        }
        phrase("Speech after a long pause")
        assertEquals(listOf("Speech after a long pause"), finals); assertTrue(errors.isEmpty())
        assertEquals(42, created.size); assertFalse(states.contains(VoiceState.IDLE))
        session.cancel(); assertEquals(0, clock.pending()); assertTrue(created.all { it.destroys == 1 })
    }

    @Test fun `release during natural endpoint waits for the last result without stopping an already ended phrase twice`() {
        start(); val native = current
        native.callbacks.endOfSpeech(); session.finish(); session.finish()
        assertEquals(VoiceState.PROCESSING, session.state); assertEquals(0, native.stops)
        native.callbacks.result("Last phrase"); native.callbacks.result("Duplicate")
        clock.advance(20_000)
        assertEquals(listOf("Last phrase"), finals); assertEquals(VoiceState.IDLE, session.state)
        assertEquals(1, created.size); assertEquals(1, native.destroys); assertEquals(0, clock.pending())
    }

    @Test fun `explicit stop between phrases cancels queued restart and preserves already committed text`() {
        start(); current.callbacks.result("Kept")
        val queued = clock.tasks.last(); session.finish(); queued.action(); clock.advance(30_000)
        assertEquals(VoiceState.IDLE, session.state); assertEquals(1, created.size)
        assertEquals(listOf("Kept"), finals); assertTrue(errors.isEmpty()); assertEquals(0, clock.pending())
    }

    @Test fun `pause cancel while rearming cannot reopen microphone or accept callbacks from old cycle`() {
        start(); val old = current; old.callbacks.result("Committed before pause")
        val queued = clock.tasks.last(); session.cancel(); queued.action(); old.callbacks.result("Stale")
        clock.advance(30_000)
        assertEquals(1, created.size); assertEquals(listOf("Committed before pause"), finals)
        assertEquals(VoiceState.IDLE, session.state); assertTrue(errors.isEmpty())
    }

    @Test fun `fatal network error retains earlier phrases and requires a deliberate new session`() {
        start(); phrase("Kept before network failure")
        current.callbacks.error(VoiceFailure.NETWORK); clock.advance(30_000)
        assertEquals(listOf("Kept before network failure"), finals); assertEquals(listOf(VoiceFailure.NETWORK), errors)
        assertEquals(VoiceState.IDLE, session.state); assertEquals(2, created.size)
        session.start(VoiceEngine.LOCAL); assertEquals(VoiceEngine.LOCAL, engines.last()); session.cancel()
    }

    @Test fun `immediate no match service loops back off and terminate instead of churning recognizers`() {
        start()
        repeat(6) { current.callbacks.error(VoiceFailure.NO_MATCH); clock.advance(clock.tasks.last().at - clock.now); current.callbacks.ready() }
        current.callbacks.error(VoiceFailure.NO_MATCH)
        assertEquals(VoiceState.IDLE, session.state); assertEquals(7, created.size)
        assertEquals(listOf(VoiceFailure.NO_MATCH), errors); assertEquals(0, clock.pending())
        assertTrue(created.all { it.destroys == 1 })
    }

    @Test fun `busy provider has only three retries and never switches from local to online`() {
        start(VoiceEngine.LOCAL)
        for (delay in listOf(500L, 1000L, 2000L)) {
            current.callbacks.error(VoiceFailure.BUSY); clock.advance(delay - 1)
            val count = created.size; clock.advance(1); assertEquals(count + 1, created.size)
        }
        current.callbacks.error(VoiceFailure.BUSY); clock.advance(30_000)
        assertEquals(4, created.size); assertTrue(engines.all { it == VoiceEngine.LOCAL })
        assertEquals(listOf(VoiceFailure.BUSY), errors); assertEquals(0, clock.pending())
    }

    @Test fun `cancelled startup and endpoint deadlines cannot fail a newer phase of the same session`() {
        session.start(VoiceEngine.ONLINE); val startup = clock.tasks.last()
        current.callbacks.ready(); startup.action()
        assertEquals(VoiceState.LISTENING, session.state); assertTrue(errors.isEmpty())
        current.callbacks.endOfSpeech(); val endpoint = clock.tasks.last()
        current.callbacks.result("Once"); clock.advance(250); current.callbacks.ready(); endpoint.action()
        assertEquals(VoiceState.LISTENING, session.state); assertEquals(listOf("Once"), finals); assertTrue(errors.isEmpty())
        session.cancel()
    }

    @Test fun `ninety second provider watchdog rotates a phrase but never finishes dictation itself`() {
        start(); val first = current; clock.advance(90_000)
        assertEquals(1, first.stops); assertEquals(VoiceState.RESTARTING, session.state)
        first.callbacks.result("Long phrase"); clock.advance(250); current.callbacks.ready()
        assertEquals(VoiceState.LISTENING, session.state); assertEquals(2, created.size)
        session.finish(); current.callbacks.error(VoiceFailure.SILENCE)
        assertEquals(VoiceState.IDLE, session.state); assertEquals(listOf("Long phrase"), finals); assertTrue(errors.isEmpty())
    }
}
