package com.notessync.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.notessync.app.i18n.uiText
import com.notessync.app.settings.VoiceEngine

/** Android adapter. Created and used on Main; no service, model download or automatic restart. */
@androidx.annotation.MainThread
class VoiceInputManager(context: Context, listener: Listener, languageTag: () -> String = { java.util.Locale.getDefault().toLanguageTag() }) {
    interface Listener {
        fun onState(state: VoiceState)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val session = VoiceSession(AndroidVoiceRecognizerFactory(context, languageTag), VoiceDeadline { delay, action ->
        val task = Runnable(action)
        handler.postDelayed(task, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(task) }
        cancel
    }, object : VoiceSession.Listener {
        override fun state(state: VoiceState) = listener.onState(state)
        override fun partial(text: String) = listener.onPartial(text)
        override fun result(text: String) = listener.onFinal(text)
        override fun error(failure: VoiceFailure) = listener.onError(message(failure))
    })
    fun isAvailable(engine: VoiceEngine) = session.available(engine)
    fun start(engine: VoiceEngine) = session.start(engine)
    fun finish() = session.finish()
    fun cancel() = session.cancel()
    fun unavailableMessage(engine: VoiceEngine): String = message(
        if (engine == VoiceEngine.LOCAL) VoiceFailure.LOCAL_UNAVAILABLE else VoiceFailure.UNAVAILABLE)

    private fun message(failure: VoiceFailure): String = when (failure) {
        VoiceFailure.AUDIO -> uiText("Ошибка записи звука")
        VoiceFailure.CLIENT -> uiText("Распознавание остановлено")
        VoiceFailure.PERMISSION -> uiText("Нужно разрешение на микрофон")
        VoiceFailure.NETWORK -> uiText("Нет сети. Используйте клавиатуру или выберите доступный локальный режим в настройках.")
        VoiceFailure.NO_MATCH -> uiText("Не удалось разобрать речь")
        VoiceFailure.BUSY -> uiText("Распознаватель занят, подождите")
        VoiceFailure.UNAVAILABLE -> uiText("Распознавание речи недоступно на этом устройстве")
        VoiceFailure.SILENCE -> uiText("Тишина. Говорите после сигнала")
        VoiceFailure.TIMEOUT -> uiText("Распознавание не ответило. Попробуйте снова или используйте клавиатуру.")
        VoiceFailure.LANGUAGE -> uiText("Для этого языка нет доступной модели. Используйте клавиатуру или выберите другой режим в настройках.")
        VoiceFailure.LOCAL_UNAVAILABLE -> uiText("Локальное распознавание недоступно. Используйте клавиатуру или выберите онлайн в настройках.")
    }
}
