package com.notessync.app.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val MAX_AUDIO_BYTES = 10L * 1024 * 1024
const val MAX_AUDIO_DURATION_MILLIS = 20 * 60 * 1000L

data class AudioDraft(val token: String, val path: String)
interface AudioDraftStore {
    fun create(token: String): AudioDraft
    fun discard(draft: AudioDraft)
}
interface AudioCaptureBackend {
    fun prepare(path: String)
    fun start()
    fun stop()
    fun release()
}
fun interface AudioCaptureFactory {
    fun create(onStop: (AudioStopReason) -> Unit): AudioCaptureBackend
}
enum class AudioStopReason { LIMIT, INTERRUPTION, ERROR }
enum class AudioCapturePhase { IDLE, PREPARING, RECORDING, SAVING }
enum class AudioCaptureMessage { PERMISSION, LOW_STORAGE, RECORD_FAILED, SAVE_FAILED, LIMIT_SAVED, INTERRUPTED_SAVED }
class AudioStorageFullException : java.io.IOException("Not enough storage for an audio recording")

data class AudioCaptureState(
    val phase: AudioCapturePhase = AudioCapturePhase.IDLE,
    val token: String? = null,
    val startedAt: Long = 0L,
    val message: AudioCaptureMessage? = null
) { val busy: Boolean get() = phase != AudioCapturePhase.IDLE }

/** Serialized capture and finalization. Requests mark the session before scheduling any IO. */
class AudioCapture(
    private val factory: AudioCaptureFactory,
    private val files: AudioDraftStore,
    private val clock: () -> Long,
    private val commit: suspend (AudioDraft) -> Boolean,
    private val beforeStart: suspend () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = dispatcher
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private class Session(val token: String) {
        val stop = AtomicBoolean(false)
        val discard = AtomicBoolean(false)
        @Volatile var reason: AudioStopReason? = null
        var draft: AudioDraft? = null
        var backend: AudioCaptureBackend? = null
        var started = false
        var result: Boolean? = null
    }
    private val active = AtomicReference<Session?>(null)
    private val mutableState = MutableStateFlow(AudioCaptureState())
    val state = mutableState.asStateFlow()

    fun start() {
        if (closed.get()) return
        val session = Session(UUID.randomUUID().toString())
        if (!active.compareAndSet(null, session)) return
        mutableState.value = AudioCaptureState(AudioCapturePhase.PREPARING, session.token)
        scope.launch {
            mutex.withLock {
                if (active.get() !== session) return@withLock
                try {
                    if (session.stop.get() || closed.get()) { complete(session, true); return@withLock }
                    beforeStart()
                    if (session.stop.get() || closed.get()) { complete(session, true); return@withLock }
                    session.draft = files.create(session.token)
                    session.backend = factory.create { reason ->
                        if (active.get() === session) {
                            session.reason = reason
                            finish(session)
                        }
                    }
                    if (session.stop.get() || closed.get()) { finalize(session); return@withLock }
                    session.backend!!.prepare(session.draft!!.path)
                    if (session.stop.get() || closed.get()) { finalize(session); return@withLock }
                    session.backend!!.start()
                    session.started = true
                    if (session.stop.get() || closed.get()) finalize(session)
                    else mutableState.value = AudioCaptureState(AudioCapturePhase.RECORDING, session.token, clock())
                } catch (error: Exception) {
                    cleanup(session)
                    complete(session, false, when (error) {
                        is SecurityException -> AudioCaptureMessage.PERMISSION
                        is AudioStorageFullException -> AudioCaptureMessage.LOW_STORAGE
                        else -> AudioCaptureMessage.RECORD_FAILED
                    })
                }
            }
        }
    }

    /** Calling this immediately marks stop, including when native preparation has not begun yet. */
    fun finish(): Deferred<Boolean> = active.get()?.let { finish(it) } ?: CompletableDeferred(true)

    private fun finish(session: Session): Deferred<Boolean> {
        session.stop.set(true)
        return scope.async(NonCancellable + worker) {
            mutex.withLock { if (active.get() !== session) session.result ?: true else finalize(session) }
        }
    }

    fun cancel(): Deferred<Boolean> {
        val session = active.get() ?: return CompletableDeferred(true)
        session.discard.set(true)
        return finish(session)
    }

    /** A final bounded cleanup may outlive the editor; microphone recording itself never continues. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        finish().invokeOnCompletion { scope.cancel() }
    }
    fun clearMessage(token: String?) {
        val current = mutableState.value
        if (current.token == token) mutableState.compareAndSet(current, current.copy(message = null))
    }

    private suspend fun finalize(session: Session): Boolean {
        if (session.discard.get() || !session.started) {
            cleanup(session); complete(session, true); return true
        }
        mutableState.value = AudioCaptureState(AudioCapturePhase.SAVING, session.token)
        // A limit/error callback can mean that the platform has already stopped. Validate the
        // finalized container in commit even if stop throws; never attach an unvalidated file.
        runCatching { session.backend?.stop() }
        runCatching { session.backend?.release() }; session.backend = null
        val saved = try { !session.discard.get() && session.draft?.let { commit(it) } == true }
        catch (_: Exception) { false }
        session.draft?.let { runCatching { files.discard(it) } }; session.draft = null
        complete(session, saved, when {
            !saved -> AudioCaptureMessage.SAVE_FAILED
            session.reason == AudioStopReason.LIMIT -> AudioCaptureMessage.LIMIT_SAVED
            session.reason != null -> AudioCaptureMessage.INTERRUPTED_SAVED
            else -> null
        })
        return saved
    }
    private fun cleanup(session: Session) {
        runCatching { session.backend?.release() }; session.backend = null
        session.draft?.let { runCatching { files.discard(it) } }; session.draft = null
    }
    private fun complete(session: Session, success: Boolean, message: AudioCaptureMessage? = null) {
        session.result = success
        if (active.compareAndSet(session, null)) mutableState.value = AudioCaptureState(token = session.token, message = message)
    }
}
