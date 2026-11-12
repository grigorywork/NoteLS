package com.notessync.app.audio

import android.app.Application
import android.media.AudioManager
import android.media.MediaRecorder
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaRecorder
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class AndroidAudioCaptureTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun recorder(backend: AudioCaptureBackend): MediaRecorder? = backend.javaClass.declaredFields.first { it.type == MediaRecorder::class.java }
        .apply { isAccessible = true }.get(backend) as MediaRecorder?
    @Test fun `Android adapter configures bounded mono AAC and releases recorder plus focus`() {
        val messages = mutableListOf<AudioStopReason>()
        val backend = AndroidAudioCaptureFactory(app).create(messages::add)
        backend.prepare(File(app.cacheDir, "audio-test.m4a").path)
        val raw = recorder(backend)!!; val simulated = shadowOf(raw)
        assertEquals(ShadowMediaRecorder.STATE_PREPARED, simulated.state)
        assertEquals(MediaRecorder.AudioSource.MIC, simulated.audioSource); assertEquals(MediaRecorder.AudioEncoder.AAC, simulated.audioEncoder)
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, simulated.outputFormat); assertEquals(1, simulated.audioChannels)
        assertEquals(64_000, simulated.audioEncodingBitRate); assertEquals(44_100, simulated.audioSamplingRate)
        assertEquals(MAX_AUDIO_DURATION_MILLIS.toInt(), simulated.maxDuration); assertTrue(simulated.maxFileSize < MAX_AUDIO_BYTES)
        backend.start(); assertEquals(ShadowMediaRecorder.STATE_RECORDING, simulated.state)
        simulated.infoListener.onInfo(raw, MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED, 0)
        simulated.errorListener.onError(raw, MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN, 0)
        assertEquals(listOf(AudioStopReason.LIMIT, AudioStopReason.ERROR), messages)
        backend.stop(); backend.release(); assertEquals(ShadowMediaRecorder.STATE_RELEASED, simulated.state)
        assertNotNull(shadowOf(app.getSystemService(AudioManager::class.java)).lastAbandonedAudioFocusRequest)
    }
    @Test fun `focus denial never creates a recorder`() {
        shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val backend = AndroidAudioCaptureFactory(app).create { }
        try { backend.prepare(File(app.cacheDir, "denied.m4a").path); fail("Recording must not start without focus") }
        catch (_: IllegalStateException) { }
        finally { backend.release() }
        assertNull(recorder(backend))
    }
}
