package com.notessync.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper

class AndroidAudioPlaybackFactory(context: Context) : AudioPlaybackFactory {
    private val app = context.applicationContext
    override fun create(callbacks: AudioPlaybackCallbacks): AudioPlaybackBackend = object : AudioPlaybackBackend {
        private val audio = app.getSystemService(AudioManager::class.java)
        private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change -> if (change < 0) callbacks.interrupted() }, Handler(Looper.getMainLooper())).build()
        private var focused = false
        private val player = MediaPlayer()
        init {
            try {
                player.setAudioAttributes(attributes)
                player.setOnPreparedListener { callbacks.prepared() }
                player.setOnCompletionListener { callbacks.completed() }
                player.setOnErrorListener { _, _, _ -> callbacks.error(); true }
            } catch (error: Exception) {
                runCatching { player.release() }
                throw error
            }
        }
        override fun prepare(path: String) { player.setDataSource(path); player.prepareAsync() }
        override fun play(positionMillis: Long) {
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw IllegalStateException("Audio focus unavailable")
            focused = true
            if (positionMillis > 0) player.seekTo(positionMillis, MediaPlayer.SEEK_CLOSEST)
            player.start()
        }
        override val positionMillis: Long get() = player.currentPosition.toLong()
        override val durationMillis: Long get() = player.duration.toLong()
        override fun release() {
            try { player.release() } finally { if (focused) { focused = false; audio.abandonAudioFocusRequest(focus) } }
        }
    }
}
