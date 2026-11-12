package com.notessync.app.voice

import com.notessync.app.settings.VoiceEngine

/** Platform boundary: one recognizer per deliberate user gesture; no automatic network fallback. */
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
enum class VoiceState { IDLE, STARTING, LISTENING, PROCESSING }
enum class VoiceFailure { AUDIO, CLIENT, PERMISSION, NETWORK, NO_MATCH, BUSY, UNAVAILABLE, SILENCE, TIMEOUT, LANGUAGE, LOCAL_UNAVAILABLE }

/** Main-thread state machine. Releasing finishes recognition; cancelling discards the session. */
class VoiceSession(
    private val factory: VoiceRecognizerFactory,
    private val deadline: VoiceDeadline,
    private val listener: Listener
) {
    interface Listener {
        fun state(state: VoiceState)
        fun partial(text: String)
        fun result(text: String)
        fun error(failure: VoiceFailure)
    }

    var state = VoiceState.IDLE
        private set
    private class Active { var recognizer: VoiceRecognizer? = null }
    private var active: Active? = null
    private var cancelDeadline: (() -> Unit)? = null

    fun available(engine: VoiceEngine): Boolean = runCatching { factory.available(engine) }.getOrDefault(false)

    fun start(engine: VoiceEngine) {
        if (active != null) return
        if (!available(engine)) {
            listener.error(if (engine == VoiceEngine.LOCAL) VoiceFailure.LOCAL_UNAVAILABLE else VoiceFailure.UNAVAILABLE)
            return
        }
        val session = Active()
        active = session
        changeState(VoiceState.STARTING)
        try {
            session.recognizer = factory.create(engine, object : VoiceCallbacks {
                override fun ready() {
                    if (active !== session || state != VoiceState.STARTING) return
                    changeState(VoiceState.LISTENING)
                    arm(session, 90_000) { finish() }
                }
                override fun partial(text: String) {
                    if (active === session && state != VoiceState.PROCESSING) listener.partial(bounded(text, 4_000))
                }
                override fun result(text: String) {
                    if (active !== session) return
                    val result = bounded(text.trim(), 32_000)
                    release(session, cancel = false)
                    if (result.isBlank()) listener.error(VoiceFailure.NO_MATCH) else listener.result(result)
                }
                override fun error(failure: VoiceFailure) { fail(session, failure) }
                override fun endOfSpeech() {
                    if (active !== session || state == VoiceState.PROCESSING) return
                    changeState(VoiceState.PROCESSING)
                    arm(session, 15_000) { fail(session, VoiceFailure.TIMEOUT) }
                }
            })
            arm(session, 10_000) { fail(session, VoiceFailure.TIMEOUT) }
            session.recognizer!!.start()
        } catch (_: SecurityException) {
            fail(session, VoiceFailure.PERMISSION)
        } catch (_: Exception) {
            fail(session, VoiceFailure.UNAVAILABLE)
        }
    }

    fun finish() {
        val session = active ?: return
        if (state == VoiceState.PROCESSING) return
        changeState(VoiceState.PROCESSING)
        arm(session, 15_000) { fail(session, VoiceFailure.TIMEOUT) }
        try { session.recognizer?.finish() }
        catch (_: SecurityException) { fail(session, VoiceFailure.PERMISSION) }
        catch (_: Exception) { fail(session, VoiceFailure.AUDIO) }
    }

    fun cancel() { active?.let { release(it, cancel = true) } }

    private fun arm(session: Active, delay: Long, action: () -> Unit) {
        cancelDeadline?.invoke()
        cancelDeadline = deadline.after(delay) { if (active === session) action() }
    }
    private fun fail(session: Active, failure: VoiceFailure) {
        if (active !== session) return
        release(session, cancel = true)
        listener.error(failure)
    }
    private fun release(session: Active, cancel: Boolean) {
        if (active !== session) return
        active = null // Invalidate callbacks before calling the platform, including synchronous callbacks.
        cancelDeadline?.invoke(); cancelDeadline = null
        if (cancel) runCatching { session.recognizer?.cancel() }
        runCatching { session.recognizer?.destroy() }
        changeState(VoiceState.IDLE)
    }
    private fun changeState(value: VoiceState) { state = value; listener.state(value) }

    private fun bounded(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (text[limit - 1].isHighSurrogate()) limit - 1 else limit
        return text.substring(0, end)
    }
}
