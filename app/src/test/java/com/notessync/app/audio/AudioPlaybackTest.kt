package com.notessync.app.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPlaybackTest {
    private class Backend(val callbacks: AudioPlaybackCallbacks) : AudioPlaybackBackend {
        val plays = mutableListOf<Long>(); var releases = 0; var path: String? = null
        var position = 0L; var reads = 0; var prepareError = false; var playError = false; var cleanupError = false
        override fun prepare(path: String) { this.path = path; if (prepareError) error("no source") }
        override fun play(positionMillis: Long) { if (playError) error("no focus"); plays += positionMillis; position = positionMillis }
        override val positionMillis: Long get() { reads++; return position }
        override val durationMillis = 10_000L
        override fun release() { releases++; if (cleanupError) error("cleanup error") }
    }
    @Test fun `pause releases decoder and focus and resume resolves access again at the stored position`() = runTest {
        val players = mutableListOf<Backend>(); var checks = 0
        val manager = AudioPlayback(AudioPlaybackFactory { Backend(it).also(players::add) }, { checks++; "/audio/$it" }, StandardTestDispatcher(testScheduler))
        manager.play("one"); runCurrent(); assertTrue(players.single().plays.isEmpty())
        players.single().callbacks.prepared(); runCurrent(); assertEquals(listOf(0L), players.single().plays)
        players.single().position = 4_200; advanceTimeBy(1_000); runCurrent()
        assertEquals(4_200L, manager.state.value.positionMillis)
        manager.pause(); runCurrent(); val reads = players.single().reads
        assertEquals(AudioPlaybackPhase.PAUSED, manager.state.value.phase); assertEquals(1, players.single().releases)
        advanceTimeBy(5_000); runCurrent(); assertEquals(reads, players.single().reads)
        val before = checks; manager.play("one"); runCurrent(); players.last().callbacks.prepared(); runCurrent()
        assertTrue(checks > before); assertEquals(listOf(4_200L), players.last().plays)
        manager.close(); runCurrent(); assertTrue(players.all { it.releases == 1 })
    }
    @Test fun `switching audio ignores all old callbacks and duplicate readiness starts playback once`() = runTest {
        val players = mutableListOf<Backend>()
        val manager = AudioPlayback(AudioPlaybackFactory { Backend(it).also(players::add) }, { "/audio/$it" }, StandardTestDispatcher(testScheduler))
        manager.play("old"); runCurrent(); val old = players.single()
        manager.play("new"); runCurrent(); val next = players.last()
        old.callbacks.prepared(); old.callbacks.completed(); old.callbacks.error(); runCurrent()
        assertEquals(1, old.releases); assertEquals("new", manager.state.value.key); assertTrue(next.plays.isEmpty())
        next.callbacks.prepared(); next.callbacks.prepared(); runCurrent(); assertEquals(1, next.plays.size)
        next.callbacks.completed(); runCurrent()
        next.callbacks.interrupted(); next.callbacks.error(); next.callbacks.prepared(); runCurrent()
        assertEquals(AudioPlaybackPhase.IDLE, manager.state.value.phase); assertFalse(manager.state.value.failed)
        manager.stop(); next.callbacks.error(); runCurrent()
        assertEquals(AudioPlaybackPhase.IDLE, manager.state.value.phase); assertFalse(manager.state.value.failed)
        manager.close(); runCurrent(); assertEquals(1, next.releases)
    }
    @Test fun `access revoked while preparing prevents sound and releases the native player`() = runTest {
        val players = mutableListOf<Backend>(); var allowed = true
        val manager = AudioPlayback(AudioPlaybackFactory { Backend(it).also(players::add) }, { if (allowed) "/audio/$it" else null }, StandardTestDispatcher(testScheduler))
        manager.play("one"); runCurrent(); allowed = false
        players.single().callbacks.prepared(); runCurrent()
        assertTrue(players.single().plays.isEmpty()); assertEquals(1, players.single().releases); assertTrue(manager.state.value.failed)
        manager.close(); runCurrent()
    }
    @Test fun `stop does not wait for a busy DB resolver and prevents a delayed player creation`() = runTest {
        val ready = CompletableDeferred<String?>(); var players = 0
        val manager = AudioPlayback(AudioPlaybackFactory { players++; Backend(it) }, { ready.await() }, StandardTestDispatcher(testScheduler))
        manager.play("one"); runCurrent(); val stop = manager.stop(); runCurrent()
        assertTrue(stop.isCompleted); assertEquals(AudioPlaybackPhase.IDLE, manager.state.value.phase)
        ready.complete("/audio/one"); runCurrent(); assertEquals(0, players)
        manager.close(); runCurrent()
    }
    @Test fun `platform preparation playback and cleanup errors cannot retain a decoder`() = runTest {
        val players = mutableListOf<Backend>(); var prepareError = true
        val manager = AudioPlayback(AudioPlaybackFactory { Backend(it).also { b -> b.prepareError = prepareError; b.cleanupError = true; players += b } },
            { "/audio/$it" }, StandardTestDispatcher(testScheduler))
        manager.play("one"); runCurrent(); assertEquals(1, players.first().releases); assertTrue(manager.state.value.failed)
        prepareError = false; manager.play("one"); runCurrent(); players.last().playError = true
        players.last().callbacks.prepared(); runCurrent(); assertEquals(1, players.last().releases); assertTrue(manager.state.value.failed)
        manager.close(); runCurrent()
    }
    @Test fun `audio focus interruption pauses without automatically restarting after late callbacks`() = runTest {
        val players = mutableListOf<Backend>()
        val manager = AudioPlayback(AudioPlaybackFactory { Backend(it).also(players::add) }, { "/audio/$it" }, StandardTestDispatcher(testScheduler))
        manager.play("one"); runCurrent(); val player = players.single(); player.callbacks.prepared(); runCurrent()
        player.position = 3_000; player.callbacks.interrupted(); runCurrent()
        assertEquals(AudioPlaybackPhase.PAUSED, manager.state.value.phase); assertEquals(3_000L, manager.state.value.positionMillis)
        player.callbacks.prepared(); player.callbacks.error(); runCurrent()
        assertEquals(1, player.plays.size); assertEquals(1, player.releases); assertFalse(manager.state.value.failed)
        manager.close(); runCurrent()
    }
}
