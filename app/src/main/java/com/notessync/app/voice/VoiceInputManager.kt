package com.notessync.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.notessync.app.settings.VoiceEngine

/**
 * Этап 32: обёртка над SpeechRecognizer.
 * Онлайн — облачное распознавание системы; локально — on-device (Android 12+),
 * с откатом к EXTRA_PREFER_OFFLINE на старых версиях.
 * Все колбэки приходят на главный поток, распознавание не блокирует ввод.
 */
class VoiceInputManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
        fun onEnd()
    }

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(engine: VoiceEngine) {
        stop()
        val preferOffline = engine == VoiceEngine.LOCAL
        val created = if (
            preferOffline && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                listener.onError(errorMessage(error))
                listener.onEnd()
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) listener.onFinal(text)
                listener.onEnd()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) listener.onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        created.startListening(intent)
    }

    fun stop() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun errorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука"
        SpeechRecognizer.ERROR_CLIENT -> "Распознавание остановлено"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нужно разрешение на микрофон"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Нет сети. Переключитесь на локальный режим в настройках"
        SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось разобрать речь"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят, подождите"
        SpeechRecognizer.ERROR_SERVER -> "Сервис распознавания недоступен"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Тишина. Говорите после сигнала"
        else -> "Ошибка распознавания ($error)"
    }
}
