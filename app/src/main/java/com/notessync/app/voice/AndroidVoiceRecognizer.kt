package com.notessync.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.notessync.app.settings.VoiceEngine
import java.util.Locale

internal class AndroidVoiceRecognizerFactory(context: Context, private val languageTag: () -> String = { Locale.getDefault().toLanguageTag() }) : VoiceRecognizerFactory {
    private val app = context.applicationContext
    override fun available(engine: VoiceEngine): Boolean = when (engine) {
        VoiceEngine.ONLINE -> SpeechRecognizer.isRecognitionAvailable(app)
        VoiceEngine.LOCAL -> Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(app)
    }
    override fun create(engine: VoiceEngine, callbacks: VoiceCallbacks): VoiceRecognizer {
        // EXTRA_PREFER_OFFLINE alone is only a hint; it must never implement the strict LOCAL mode.
        val recognizer = if (engine == VoiceEngine.LOCAL) {
            if (Build.VERSION.SDK_INT >= 31) {
                check(SpeechRecognizer.isOnDeviceRecognitionAvailable(app))
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            } else throw IllegalStateException("On-device recognition requires Android 12")
        } else SpeechRecognizer.createSpeechRecognizer(app)
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = callbacks.ready()
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = callbacks.endOfSpeech()
                override fun onError(error: Int) = callbacks.error(failure(error))
                override fun onResults(results: Bundle?) = callbacks.result(text(results))
                override fun onPartialResults(partialResults: Bundle?) = callbacks.partial(text(partialResults))
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        } catch (error: Exception) {
            runCatching { recognizer.destroy() }
            throw error
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (engine == VoiceEngine.LOCAL) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        return object : VoiceRecognizer {
            override fun start() = recognizer.startListening(intent)
            override fun finish() = recognizer.stopListening()
            override fun cancel() = recognizer.cancel()
            override fun destroy() = recognizer.destroy()
        }
    }
    private fun text(bundle: Bundle?): String = runCatching {
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { !it.isNullOrBlank() }.orEmpty()
    }.getOrDefault("")

    private fun failure(error: Int): VoiceFailure = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> VoiceFailure.AUDIO
        SpeechRecognizer.ERROR_CLIENT -> VoiceFailure.CLIENT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceFailure.NETWORK
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceFailure.NO_MATCH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceFailure.BUSY
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.SILENCE
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceFailure.LANGUAGE
        else -> VoiceFailure.UNAVAILABLE
    }
}
