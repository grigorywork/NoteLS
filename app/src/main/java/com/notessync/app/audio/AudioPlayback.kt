package com.notessync.app.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface AudioPlaybackCallbacks {
    fun prepared()
    fun completed()
    fun error()
    fun interrupted()
}
interface AudioPlaybackBackend {
    fun prepare(path: String)
    fun play(positionMillis: Long)
    val positionMillis: Long
    val durationMillis: Long
    fun release()
}
fun interface AudioPlaybackFactory { fun create(callbacks: AudioPlaybackCallbacks): AudioPlaybackBackend }
enum class AudioPlaybackPhase { IDLE, PREPARING, PLAYING, PAUSED }
data class AudioPlaybackState(val key: String? = null, val phase: AudioPlaybackPhase = AudioPlaybackPhase.IDLE,
    val positionMillis: Long = 0, val durationMillis: Long = 0, val failed: Boolean = false) {
    val active: Boolean get() = phase == AudioPlaybackPhase.PREPARING || phase == AudioPlaybackPhase.PLAYING
}

/** Resolve access afresh on every play/resume. A paused player retains only a position, no decoder/focus. */
class AudioPlayback(private val factory: AudioPlaybackFactory, private val resolve: suspend (String) -> String?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = dispatcher
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean(false)
    private var backend: AudioPlaybackBackend? = null
    private var ticker: Job? = null
    private val mutableState = MutableStateFlow(AudioPlaybackState())
    val state = mutableState.asStateFlow()

    fun play(key: String) {
        if (closed.get()) return
        val stamp = generation.incrementAndGet()
        scope.launch {
            val resume = mutex.withLock {
                if (generation.get() != stamp) return@launch
                val old = mutableState.value
                release()
                mutableState.value = AudioPlaybackState(key, AudioPlaybackPhase.PREPARING)
                if (old.key == key && old.phase == AudioPlaybackPhase.PAUSED) old.positionMillis else 0
            }
            // Never hold the player mutex during a potentially busy DB access check.
            val path = try { resolve(key) } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            mutex.withLock {
                if (generation.get() != stamp || closed.get()) return@withLock
                if (path == null) { fail(key); return@withLock }
                try {
                    val created = factory.create(object : AudioPlaybackCallbacks {
                        override fun prepared() { scope.launch {
                            val freshPath = try { resolve(key) } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                null
                            }
                            mutex.withLock {
                                if (generation.get() != stamp || closed.get() || mutableState.value.phase != AudioPlaybackPhase.PREPARING) return@withLock
                                if (freshPath != path) { fail(key); return@withLock }
                                try {
                                    val player = backend ?: return@withLock
                                    val duration = player.durationMillis.coerceAtLeast(0)
                                    player.play(resume.coerceIn(0, duration))
                                    mutableState.value = AudioPlaybackState(key, AudioPlaybackPhase.PLAYING, resume.coerceIn(0, duration), duration)
                                    ticker = scope.launch {
                                        while (isActive && generation.get() == stamp) {
                                            delay(1_000)
                                            mutex.withLock {
                                                if (generation.get() != stamp || mutableState.value.phase != AudioPlaybackPhase.PLAYING) return@launch
                                                val position = runCatching { backend?.positionMillis ?: 0 }.getOrDefault(0)
                                                mutableState.value = mutableState.value.copy(positionMillis = position.coerceIn(0, mutableState.value.durationMillis))
                                            }
                                        }
                                    }
                                } catch (_: Exception) { fail(key) }
                            }
                        } }
                        override fun completed() { scope.launch {
                            mutex.withLock {
                                if (generation.get() != stamp || backend == null) return@withLock
                                val previous = mutableState.value
                                release(); mutableState.value = previous.copy(phase = AudioPlaybackPhase.IDLE, positionMillis = previous.durationMillis)
                            }
                        } }
                        override fun error() { scope.launch { mutex.withLock { if (generation.get() == stamp && backend != null) fail(key) } } }
                        override fun interrupted() { if (generation.get() == stamp && mutableState.value.active) pause() }
                    })
                    backend = created
                    created.prepare(path)
                } catch (_: Exception) { fail(key) }
            }
        }
    }
    fun pause(): Deferred<Unit> {
        val stamp = generation.incrementAndGet()
        return scope.async(NonCancellable + worker) {
            mutex.withLock {
                if (generation.get() != stamp) return@withLock
                val previous = mutableState.value
                val position = runCatching { backend?.positionMillis ?: previous.positionMillis }.getOrDefault(previous.positionMillis)
                release()
                mutableState.value = previous.copy(phase = AudioPlaybackPhase.PAUSED, positionMillis = position.coerceIn(0, previous.durationMillis))
            }
        }
    }
    fun stop(): Deferred<Unit> {
        val stamp = generation.incrementAndGet()
        return scope.async(NonCancellable + worker) {
            mutex.withLock { if (generation.get() == stamp) { release(); mutableState.value = AudioPlaybackState() } }
        }
    }
    fun clearError() { val old = mutableState.value; mutableState.compareAndSet(old, old.copy(failed = false)) }
    fun close() { if (closed.compareAndSet(false, true)) stop().invokeOnCompletion { scope.cancel() } }
    private fun fail(key: String) { release(); mutableState.value = AudioPlaybackState(key = key, failed = true) }
    private fun release() { ticker?.cancel(); ticker = null; runCatching { backend?.release() }; backend = null }
}
