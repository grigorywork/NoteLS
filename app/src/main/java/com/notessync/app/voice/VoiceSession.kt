package com.notessync.app.voice

import com.notessync.app.settings.VoiceEngine

/** Platform boundary. A deliberate foreground session can contain several recognition phrases. */
interface VoiceRecognizerFactory {
    fun available(engine: VoiceEngine): Boolean
    fun create(engine: VoiceEngine, callbacks: VoiceCallbacks): VoiceRecognizer
}

interface VoiceRecognizer {
    fun start()
    fun finish()
    fun cancel()
    fun destroy()
}

interface VoiceCallbacks {
    fun ready()
    fun partial(text: String)
    fun result(text: String)
    fun error(failure: VoiceFailure)
    fun endOfSpeech()
}

fun interface VoiceDeadline { fun after(delayMillis: Long, action: () -> Unit): () -> Unit }
enum class VoiceState { IDLE, STARTING, LISTENING, RESTARTING, PROCESSING }
enum class VoiceFailure { AUDIO, CLIENT, PERMISSION, NETWORK, NO_MATCH, BUSY, UNAVAILABLE, SILENCE, TIMEOUT, LANGUAGE, LOCAL_UNAVAILABLE }

/** Main-thread owner: a provider endpoint ends one phrase, never the user's ongoing gesture. */
class VoiceSession(
    private val factory: VoiceRecognizerFactory,
    private val deadline: VoiceDeadline,
    private val listener: Listener,
    private val uptime: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    interface Listener {
        fun state(state: VoiceState)
        fun partial(text: String)
        /** Each confirmed phrase is delivered once, so a later service error cannot discard it. */
        fun result(text: String)
        fun error(failure: VoiceFailure)
    }

    var state = VoiceState.IDLE
        private set
    private enum class Phase { STARTING, LISTENING, ENDING }
    private class Cycle(val startedAt: Long) {
        var recognizer: VoiceRecognizer? = null
        var phase = Phase.STARTING
    }
    private class Active(val engine: VoiceEngine) {
        var cycle: Cycle? = null
        var finishing = false
        var hasResults = false
        var busyRetries = 0
        var rapidEmptyRetries = 0
    }
    private var active: Active? = null
    private var cancelDeadline: (() -> Unit)? = null
    private var timerGeneration = 0L

    fun available(engine: VoiceEngine): Boolean = runCatching { factory.available(engine) }.getOrDefault(false)

    fun start(engine: VoiceEngine) {
        if (active != null) return
        if (!available(engine)) {
            listener.error(if (engine == VoiceEngine.LOCAL) VoiceFailure.LOCAL_UNAVAILABLE else VoiceFailure.UNAVAILABLE)
            return
        }
        Active(engine).also { active = it; begin(it) }
    }

    private fun begin(session: Active) {
        if (active !== session || session.finishing) return
        val cycle = Cycle(uptime())
        session.cycle = cycle
        changeState(VoiceState.STARTING)
        if (!current(session, cycle)) return
        arm(session, 10_000) { fail(session, VoiceFailure.TIMEOUT) }
        try {
            val recognizer = factory.create(session.engine, object : VoiceCallbacks {
                override fun ready() {
                    if (!current(session, cycle) || session.finishing || cycle.phase != Phase.STARTING) return
                    cycle.phase = Phase.LISTENING
                    changeState(VoiceState.LISTENING)
                    // Rotate a very long provider phrase, without ending the foreground dictation.
                    arm(session, 90_000) { endPhrase(session, cycle, stop = true) }
                }
                override fun partial(text: String) {
                    if (current(session, cycle) && !session.finishing) listener.partial(bounded(text, 4_000))
                }
                override fun result(text: String) {
                    if (!current(session, cycle)) return
                    val phrase = bounded(text.trim(), 32_000)
                    if (phrase.isBlank()) { phraseError(session, cycle, VoiceFailure.NO_MATCH); return }
                    session.hasResults = true; session.busyRetries = 0; session.rapidEmptyRetries = 0
                    clearTimer(); disposeCycle(session, cycle, cancel = false)
                    listener.partial("")
                    if (session.finishing) release(session, cancel = false)
                    listener.result(phrase)
                    if (active === session) restart(session, 250)
                }
                override fun error(failure: VoiceFailure) {
                    if (current(session, cycle)) phraseError(session, cycle, failure)
                }
                override fun endOfSpeech() {
                    if (current(session, cycle)) endPhrase(session, cycle, stop = false)
                }
            })
            // Defensive against a factory delivering a synchronous terminal callback during creation.
            if (!current(session, cycle)) { runCatching { recognizer.destroy() }; return }
            cycle.recognizer = recognizer
            recognizer.start()
        } catch (_: SecurityException) { fail(session, VoiceFailure.PERMISSION) }
        catch (_: Exception) { fail(session, VoiceFailure.UNAVAILABLE) }
    }

    private fun endPhrase(session: Active, cycle: Cycle, stop: Boolean) {
        if (!current(session, cycle) || cycle.phase == Phase.ENDING) return
        cycle.phase = Phase.ENDING
        changeState(if (session.finishing) VoiceState.PROCESSING else VoiceState.RESTARTING)
        arm(session, 15_000) { fail(session, VoiceFailure.TIMEOUT) }
        if (stop && current(session, cycle)) try { cycle.recognizer?.finish() }
        catch (_: SecurityException) { fail(session, VoiceFailure.PERMISSION) }
        catch (_: Exception) { fail(session, VoiceFailure.AUDIO) }
    }

    private fun phraseError(session: Active, cycle: Cycle, failure: VoiceFailure) {
        if (!current(session, cycle)) return
        if (failure == VoiceFailure.SILENCE || failure == VoiceFailure.NO_MATCH) {
            if (session.finishing) {
                val hadResults = session.hasResults
                release(session, cancel = true)
                if (!hadResults) listener.error(failure)
                return
            }
            // Ordinary quiet endpoints can repeat for as long as the user keeps dictation active.
            // A broken service returning immediately is backed off and eventually stopped.
            session.rapidEmptyRetries = if (uptime() - cycle.startedAt < 1_000) session.rapidEmptyRetries + 1 else 0
            if (session.rapidEmptyRetries > 6) { fail(session, failure); return }
            clearTimer(); disposeCycle(session, cycle, cancel = true); listener.partial("")
            val delay = if (session.rapidEmptyRetries == 0) 250L else (250L shl session.rapidEmptyRetries.coerceAtMost(3))
            restart(session, delay)
        } else if (failure == VoiceFailure.BUSY && !session.finishing && session.busyRetries < 3) {
            session.busyRetries++
            clearTimer(); disposeCycle(session, cycle, cancel = true); listener.partial("")
            restart(session, 500L shl (session.busyRetries - 1))
        } else fail(session, failure)
    }

    private fun restart(session: Active, delay: Long) {
        if (active !== session || session.finishing) return
        changeState(VoiceState.RESTARTING)
        arm(session, delay) { begin(session) }
    }

    fun finish() {
        val session = active ?: return
        if (session.finishing) return
        session.finishing = true
        val cycle = session.cycle
        if (cycle == null) { release(session, cancel = false); return }
        changeState(VoiceState.PROCESSING)
        if (cycle.phase == Phase.ENDING) arm(session, 15_000) { fail(session, VoiceFailure.TIMEOUT) }
        else endPhrase(session, cycle, stop = true)
    }

    /** Cancels the unconfirmed phrase; text already delivered to the editor remains user data. */
    fun cancel() { active?.let { release(it, cancel = true) } }

    private fun current(session: Active, cycle: Cycle) = active === session && session.cycle === cycle
    private fun clearTimer() {
        timerGeneration++
        cancelDeadline?.invoke(); cancelDeadline = null
    }
    private fun arm(session: Active, delay: Long, action: () -> Unit) {
        if (active !== session) return
        clearTimer()
        val token = timerGeneration
        cancelDeadline = deadline.after(delay) { if (active === session && timerGeneration == token) action() }
    }
    private fun fail(session: Active, failure: VoiceFailure) {
        if (active !== session) return
        release(session, cancel = true)
        listener.error(failure)
    }
    private fun disposeCycle(session: Active, cycle: Cycle, cancel: Boolean) {
        if (session.cycle !== cycle) return
        session.cycle = null // Invalidate callbacks before platform cancel/destroy can call back.
        if (cancel) runCatching { cycle.recognizer?.cancel() }
        runCatching { cycle.recognizer?.destroy() }
    }
    private fun release(session: Active, cancel: Boolean) {
        if (active !== session) return
        active = null
        clearTimer()
        session.cycle?.let { disposeCycle(session, it, cancel) }
        listener.partial("")
        changeState(VoiceState.IDLE)
    }
    private fun changeState(value: VoiceState) { state = value; listener.state(value) }
    private fun bounded(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }
}
