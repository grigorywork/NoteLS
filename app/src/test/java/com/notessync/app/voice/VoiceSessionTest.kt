package com.notessync.app.voice

import com.notessync.app.settings.VoiceEngine
import org.junit.Test
import org.junit.Assert.*

class VoiceSessionTest {
    private class Clock : VoiceDeadline {
        data class Job(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val jobs = mutableListOf<Job>()
        override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
            val job = Job(delayMillis, action); jobs += job
            return { job.cancelled = true }
        }
        fun fire() { jobs.last { !it.cancelled }.let { it.cancelled = true; it.action() } }
    }
    private class Recognizer(val callbacks: VoiceCallbacks) : VoiceRecognizer {
        var starts = 0; var finishes = 0; var cancels = 0; var destroys = 0
        var startError: Exception? = null; var finishError: Exception? = null; var cleanupError = false
        override fun start() { starts++; startError?.let { throw it } }
        override fun finish() { finishes++; finishError?.let { throw it } }
        override fun cancel() { cancels++; if (cleanupError) error("cancel failed") }
        override fun destroy() { destroys++; if (cleanupError) error("destroy failed") }
    }
    private class Factory : VoiceRecognizerFactory {
        var online = true; var local = true; var createError: Exception? = null
        var startError: Exception? = null
        val created = mutableListOf<Recognizer>()
        val engines = mutableListOf<VoiceEngine>()
        override fun available(engine: VoiceEngine) = if (engine == VoiceEngine.LOCAL) local else online
        override fun create(engine: VoiceEngine, callbacks: VoiceCallbacks): VoiceRecognizer {
            createError?.let { throw it }
            engines += engine
            return Recognizer(callbacks).also { it.startError = startError; created += it }
        }
    }
    private class Results : VoiceSession.Listener {
        val states = mutableListOf<VoiceState>(); val finals = mutableListOf<String>()
        val partials = mutableListOf<String>(); val errors = mutableListOf<VoiceFailure>()
        override fun state(state: VoiceState) { states += state }
        override fun partial(text: String) { partials += text }
        override fun result(text: String) { finals += text }
        override fun error(failure: VoiceFailure) { errors += failure }
    }
    private val factory = Factory(); private val clock = Clock(); private val results = Results()
    private val session = VoiceSession(factory, clock, results)
    private val current get() = factory.created.last()

    @Test fun `release before ready waits for one final without reopening or restarting the microphone`() {
        session.start(VoiceEngine.ONLINE); session.finish(); val recognizer = current
        assertEquals(1, recognizer.finishes); assertEquals(0, recognizer.destroys)
        recognizer.callbacks.ready(); recognizer.callbacks.partial("late draft")
        assertEquals(VoiceState.PROCESSING, session.state); assertTrue(results.partials.isEmpty())
        session.finish(); session.start(VoiceEngine.ONLINE)
        assertEquals(1, factory.created.size); assertEquals(1, recognizer.finishes)
        recognizer.callbacks.result("Final text"); recognizer.callbacks.result("Duplicate"); recognizer.callbacks.error(VoiceFailure.NETWORK)
        assertEquals(listOf("Final text"), results.finals); assertTrue(results.errors.isEmpty())
        assertEquals(VoiceState.IDLE, session.state); assertEquals(1, recognizer.destroys)
        assertTrue(clock.jobs.all { it.cancelled })
    }
    @Test fun `cancel on leaving the screen discards partial text and all callbacks from the old session`() {
        session.start(VoiceEngine.LOCAL); val old = current
        old.callbacks.ready(); old.callbacks.partial("Draft"); session.cancel(); session.cancel()
        assertEquals(1, old.cancels); assertEquals(1, old.destroys)
        session.start(VoiceEngine.ONLINE); val next = current
        old.callbacks.ready(); old.callbacks.result("Do not insert"); old.callbacks.error(VoiceFailure.AUDIO)
        clock.jobs.first().action() // A queued but cancelled timer must not affect the new session.
        assertEquals(VoiceState.STARTING, session.state); assertEquals(0, next.destroys)
        next.callbacks.result("New text")
        assertEquals(listOf("New text"), results.finals); assertTrue(results.errors.isEmpty())
    }
    @Test fun `missing local engine never creates an online recognizer and explicit online remains available`() {
        factory.local = false
        session.start(VoiceEngine.LOCAL)
        assertEquals(listOf(VoiceFailure.LOCAL_UNAVAILABLE), results.errors); assertTrue(factory.created.isEmpty())
        assertTrue(clock.jobs.isEmpty()); assertEquals(VoiceState.IDLE, session.state)
        session.start(VoiceEngine.ONLINE); assertEquals(listOf(VoiceEngine.ONLINE), factory.engines); session.cancel()
    }
    @Test fun `startup or final result timeout releases the recognizer and permits a manual retry`() {
        session.start(VoiceEngine.ONLINE); clock.fire()
        assertEquals(1, current.cancels); assertEquals(1, current.destroys); assertEquals(VoiceState.IDLE, session.state)
        session.start(VoiceEngine.LOCAL); current.callbacks.ready(); current.callbacks.endOfSpeech()
        assertEquals(VoiceState.PROCESSING, session.state); assertEquals(15_000L, clock.jobs.last().delay)
        clock.fire(); assertEquals(1, current.destroys)
        assertEquals(listOf(VoiceFailure.TIMEOUT, VoiceFailure.TIMEOUT), results.errors)
    }
    @Test fun `bounded listening automatically finishes once and still accepts the final result`() {
        session.start(VoiceEngine.ONLINE); current.callbacks.ready()
        assertEquals(90_000L, clock.jobs.last().delay)
        current.callbacks.ready(); clock.fire()
        assertEquals(VoiceState.PROCESSING, session.state); assertEquals(1, current.finishes)
        current.callbacks.endOfSpeech(); current.callbacks.result("Long dictation")
        assertEquals(listOf("Long dictation"), results.finals); assertTrue(results.errors.isEmpty())
    }
    @Test fun `platform failures and cleanup exceptions never retain a microphone or accept late text`() {
        session.start(VoiceEngine.ONLINE); val recognizer = current; recognizer.cleanupError = true
        recognizer.callbacks.error(VoiceFailure.PERMISSION)
        assertEquals(1, recognizer.cancels); assertEquals(1, recognizer.destroys); assertEquals(VoiceState.IDLE, session.state)
        recognizer.callbacks.result("late"); assertTrue(results.finals.isEmpty())
        session.start(VoiceEngine.ONLINE); current.finishError = IllegalStateException("stop failed"); session.finish()
        assertEquals(1, current.destroys); assertEquals(listOf(VoiceFailure.PERMISSION, VoiceFailure.AUDIO), results.errors)
    }
    @Test fun `creation and start exceptions return to idle without scheduled work`() {
        factory.createError = IllegalStateException("no service"); session.start(VoiceEngine.ONLINE)
        assertEquals(VoiceState.IDLE, session.state); assertTrue(clock.jobs.isEmpty())
        factory.createError = null; factory.startError = SecurityException("permission revoked"); session.start(VoiceEngine.ONLINE)
        assertEquals(1, current.cancels); assertEquals(1, current.destroys); assertTrue(clock.jobs.all { it.cancelled })
        assertEquals(listOf(VoiceFailure.UNAVAILABLE, VoiceFailure.PERMISSION), results.errors)
    }
    @Test fun `empty final text is not inserted and closes the session`() {
        session.start(VoiceEngine.ONLINE); current.callbacks.result(" \n ")
        assertTrue(results.finals.isEmpty()); assertEquals(listOf(VoiceFailure.NO_MATCH), results.errors)
        assertEquals(1, current.destroys); assertEquals(VoiceState.IDLE, session.state)
    }
    @Test fun `partial and final previews have Unicode safe memory limits and preserve user text`() {
        session.start(VoiceEngine.LOCAL); current.callbacks.ready()
        val partial = "a".repeat(3999) + "😀" + "b".repeat(5000)
        current.callbacks.partial(partial)
        assertEquals(3999, results.partials.single().length); assertFalse(results.partials.single().last().isHighSurrogate())
        val text = "Удалить %1" + '$' + "s " + "x".repeat(32_000)
        current.callbacks.result(text)
        assertEquals(text.take(32_000), results.finals.single())
        session.start(VoiceEngine.LOCAL)
        current.callbacks.result("a".repeat(31_999) + "😀z")
        assertEquals(31_999, results.finals.last().length); assertFalse(results.finals.last().last().isHighSurrogate())
    }
}
