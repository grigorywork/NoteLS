package com.notessync.app.audio

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Optional platform adapters for deterministic editor integration tests. Production uses Android. */
data class EditorAudioDependencies(val files: AudioFiles, val captureFactory: AudioCaptureFactory,
    val playbackFactory: AudioPlaybackFactory, val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val clock: () -> Long = android.os.SystemClock::elapsedRealtime)
