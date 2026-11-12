package com.notessync.app.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureTest {
    private class Files : AudioDraftStore {
        val drafts = mutableListOf<AudioDraft>(); val removed = mutableListOf<AudioDraft>()
        var full = false
        override fun create(token: String): AudioDraft {
            if (full) throw AudioStorageFullException()
            return AudioDraft(token, "/private/$token").also { drafts += it }
        }
        override fun discard(draft: AudioDraft) { removed += draft }
    }
    private class Backend(val stopCallback: (AudioStopReason) -> Unit) : AudioCaptureBackend {
        var starts = 0; var stops = 0; var releases = 0
        var startError: Exception? = null; var stopError = false; var releaseError = false
        var prepareAction: () -> Unit = {}
        override fun prepare(path: String) { prepareAction() }
        override fun start() { starts++; startError?.let { throw it } }
        override fun stop() { stops++; if (stopError) error("already stopped") }
        override fun release() { releases++; if (releaseError) error("release error") }
    }
    @Test fun `stop before preparation prevents a late microphone start and creates no file`() = runTest {
        val files = Files(); var created = 0
        val manager = AudioCapture(AudioCaptureFactory { created++; Backend(it) }, files, { 100 }, { true }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); val stopped = manager.finish(); runCurrent()
        assertTrue(stopped.await()); assertEquals(0, created); assertTrue(files.drafts.isEmpty()); assertFalse(manager.state.value.busy)
        manager.close(); runCurrent()
    }
    @Test fun `one stop finalizes and commits once despite duplicate clicks or platform limit callbacks`() = runTest {
        val files = Files(); val backends = mutableListOf<Backend>(); var commits = 0
        val manager = AudioCapture(AudioCaptureFactory { Backend(it).also(backends::add) }, files, { 1234 }, { commits++; true }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); manager.start(); runCurrent()
        assertEquals(1, backends.size); assertEquals(1234L, manager.state.value.startedAt)
        backends.single().stopCallback(AudioStopReason.LIMIT)
        val finish = manager.finish(); runCurrent()
        assertTrue(finish.await()); assertEquals(1, commits); assertEquals(1, backends.single().stops); assertEquals(1, backends.single().releases)
        assertEquals(files.drafts, files.removed); assertEquals(AudioCaptureMessage.LIMIT_SAVED, manager.state.value.message)
        manager.close(); runCurrent()
    }
    @Test fun `cancel discards sound and old callbacks cannot affect a new recording`() = runTest {
        val files = Files(); val backends = mutableListOf<Backend>(); var commits = 0
        val manager = AudioCapture(AudioCaptureFactory { Backend(it).also(backends::add) }, files, { 100 }, { commits++; true }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); runCurrent(); manager.cancel(); runCurrent()
        assertEquals(0, commits); assertEquals(1, backends.first().releases)
        manager.start(); runCurrent(); backends.first().stopCallback(AudioStopReason.ERROR); runCurrent()
        assertEquals(AudioCapturePhase.RECORDING, manager.state.value.phase); assertEquals(0, backends.last().releases)
        manager.close(); runCurrent(); assertEquals(1, commits); assertEquals(1, backends.last().releases)
        manager.start(); runCurrent(); assertEquals(2, backends.size)
    }
    @Test fun `stop while waiting for the note write never starts a microphone after leaving`() = runTest {
        val files = Files(); val ready = CompletableDeferred<Unit>(); var starts = 0
        val manager = AudioCapture(AudioCaptureFactory { starts++; Backend(it) }, files, { 100 }, { true },
            beforeStart = { ready.await() }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); runCurrent(); val stopped = manager.finish(); ready.complete(Unit); runCurrent()
        assertTrue(stopped.await()); assertEquals(0, starts); assertTrue(files.drafts.isEmpty())
        manager.close(); runCurrent()
    }
    @Test fun `storage or permission failures release partially prepared resources and permit manual retry`() = runTest {
        val files = Files(); files.full = true
        val backends = mutableListOf<Backend>()
        val manager = AudioCapture(AudioCaptureFactory { Backend(it).also { b -> b.startError = SecurityException(); b.releaseError = true; backends += b } },
            files, { 100 }, { true }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); runCurrent(); assertEquals(AudioCaptureMessage.LOW_STORAGE, manager.state.value.message); assertTrue(backends.isEmpty())
        files.full = false; manager.start(); runCurrent()
        assertEquals(AudioCaptureMessage.PERMISSION, manager.state.value.message); assertEquals(1, backends.single().releases)
        assertEquals(files.drafts, files.removed); assertFalse(manager.state.value.busy)
        manager.close(); runCurrent()
    }
    @Test fun `a platform stop exception still validates output and a failed DB commit removes the draft`() = runTest {
        val files = Files(); lateinit var backend: Backend; var commits = 0
        val manager = AudioCapture(AudioCaptureFactory { Backend(it).also { b -> b.stopError = true; backend = b } }, files, { 100 },
            { commits++; throw java.io.IOException("SQL failure") }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); runCurrent(); val saved = manager.finish(); runCurrent()
        assertFalse(saved.await()); assertEquals(1, commits); assertEquals(1, backend.releases); assertEquals(files.drafts, files.removed)
        assertEquals(AudioCaptureMessage.SAVE_FAILED, manager.state.value.message)
        manager.close(); runCurrent()
    }
    @Test fun `stop during native preparation prevents the following microphone start`() = runTest {
        val files = Files(); lateinit var backend: Backend; lateinit var manager: AudioCapture
        manager = AudioCapture(AudioCaptureFactory { Backend(it).also { b -> backend = b; b.prepareAction = { manager.finish() } } },
            files, { 100 }, { fail("Prepared-only audio must never be attached"); false }, dispatcher = StandardTestDispatcher(testScheduler))
        manager.start(); runCurrent()
        assertEquals(0, backend.starts); assertEquals(1, backend.releases); assertEquals(files.drafts, files.removed)
        assertFalse(manager.state.value.busy); assertNull(manager.state.value.message)
        manager.close(); runCurrent()
    }

}
