package com.notessync.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper

/** All native operations are serialized on IO by AudioCapture; callbacks only request finalization. */
class AndroidAudioCaptureFactory(context: Context) : AudioCaptureFactory {
    private val app = context.applicationContext
    override fun create(onStop: (AudioStopReason) -> Unit): AudioCaptureBackend = object : AudioCaptureBackend {
        private val audio = app.getSystemService(AudioManager::class.java)
        private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change -> if (change < 0) onStop(AudioStopReason.INTERRUPTION) }, Handler(Looper.getMainLooper()))
            .build()
        private var recorder: MediaRecorder? = null
        private var focused = false
        override fun prepare(path: String) {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IllegalStateException("Audio focus unavailable")
            focused = true
            val created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder = created
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(44_100)
            created.setAudioEncodingBitRate(64_000)
            created.setMaxDuration(MAX_AUDIO_DURATION_MILLIS.toInt())
            created.setMaxFileSize(MAX_AUDIO_BYTES - 64 * 1024)
            created.setOutputFile(path)
            created.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onStop(AudioStopReason.LIMIT)
            }
            created.setOnErrorListener { _, _, _ -> onStop(AudioStopReason.ERROR) }
            created.prepare()
        }
        override fun start() { recorder!!.start() }
        override fun stop() { recorder?.stop() }
        override fun release() {
            try { recorder?.release() } finally {
                recorder = null
                if (focused) { focused = false; audio.abandonAudioFocusRequest(focus) }
            }
        }
    }
}
