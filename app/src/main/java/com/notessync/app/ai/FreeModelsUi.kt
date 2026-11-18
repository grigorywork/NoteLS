package com.notessync.app.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.uiText

internal val AiProvider.isAdditionalFreeModel get() = this == AiProvider.GEMINI_FREE || isOpenRouter

internal fun createTransformTransport(provider: AiProvider): TransformTransport = when (provider) {
    AiProvider.GROQ_QWEN -> GroqQwenTransport()
    AiProvider.GEMINI_FREE, AiProvider.OPENROUTER_GEMMA, AiProvider.OPENROUTER_NEMOTRON -> FreeModelsTransport(provider)
    AiProvider.OPENAI, AiProvider.LOCAL_RULES -> OpenAiTransport()
}

internal fun newTransformKeyLabel(provider: AiProvider): String = when (provider) {
    AiProvider.GROQ_QWEN -> uiText("Новый API-ключ Groq")
    AiProvider.GEMINI_FREE, AiProvider.OPENROUTER_GEMMA, AiProvider.OPENROUTER_NEMOTRON ->
        uiText("Новый API-ключ %1\$s", provider.serviceName)
    else -> uiText("Новый API-ключ OpenAI")
}

internal fun transformSendLabel(provider: AiProvider): String = when (provider) {
    AiProvider.LOCAL_RULES -> uiText("Преобразовать")
    AiProvider.GROQ_QWEN -> uiText("Отправить в Groq")
    AiProvider.OPENAI -> uiText("Отправить в OpenAI")
    else -> uiText("Отправить в %1\$s", provider.serviceName)
}

internal fun transformNetworkDisclosure(provider: AiProvider): String = when (provider) {
    AiProvider.GROQ_QWEN -> uiText("Этот текст будет отправлен в Groq для Qwen. Бесплатно только на тарифе Free в пределах квоты; хранение — по условиям Groq.")
    AiProvider.GEMINI_FREE, AiProvider.OPENROUTER_GEMMA, AiProvider.OPENROUTER_NEMOTRON ->
        uiText("Этот текст будет отправлен в %1\$s. Бесплатные квоты и хранение — по условиям сервиса.", provider.serviceName)
    else -> uiText("Этот текст будет отправлен в OpenAI. Оплата и хранение — по условиям вашего аккаунта.")
}

internal fun additionalFreeProblemLabel(problem: TransformProblem): String? = when (problem) {
    TransformProblem.BILLING -> uiText("Бесплатный доступ недоступен этому аккаунту. Проверьте квоту и тариф сервиса.")
    TransformProblem.MODEL_UNAVAILABLE -> uiText("Выбранная бесплатная модель сейчас недоступна. Повторите позже.")
    TransformProblem.REGION -> uiText("API выбранного сервиса недоступен в регионе этого подключения.")
    TransformProblem.BAD_REQUEST -> uiText("Сервис не принял запрос выбранной модели.")
    TransformProblem.SERVICE_UNAVAILABLE -> uiText("Выбранный сервис временно недоступен. Повторите позже.")
    TransformProblem.OUTPUT_LIMIT, TransformProblem.INCOMPLETE -> uiText("Ответ неполный. Сократите текст и отправьте заново.")
    else -> null
}

@Composable
internal fun FreeModelSettingsInfo(provider: AiProvider) {
    Text(requireNotNull(provider.fixedModel), style = MaterialTheme.typography.bodySmall)
    Text(uiText(if (provider.isOpenRouter)
        "Фиксированная модель :free с нулевой ценой запроса. Доступ зависит от бесплатной квоты OpenRouter."
    else "Gemini бесплатно в пределах Free tier. Используйте проект без подключённого биллинга; приложение не проверяет тариф."),
        style = MaterialTheme.typography.bodySmall)
    com.notessync.app.ui.settings.SettingsHelp("free-model-access", uiText("О бесплатном доступе")) {
        Text(uiText("Запрос отправляется только вручную. При лимите или недоступности модели запрос остановится; другой модели, платного перехода и автоматического повтора нет."),
            style = MaterialTheme.typography.bodySmall)
        FreeModelProviderLinks(provider)
    }
    Text(uiText("Только выбранный текст отправляется в %1\$s после нажатия отправки.", provider.serviceName),
        style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FreeModelProviderLinks(provider: AiProvider) {
    val uri = LocalUriHandler.current
    val router = provider.isOpenRouter
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { uri.openUri(if (router) "https://openrouter.ai/settings/keys" else "https://aistudio.google.com/apikey") }) {
            Text(uiText("Получить API-ключ"))
        }
        TextButton(onClick = { uri.openUri(if (router) "https://openrouter.ai/terms" else "https://ai.google.dev/gemini-api/terms") }) { Text(uiText("Условия")) }
        TextButton(onClick = { uri.openUri(if (router) "https://openrouter.ai/privacy" else "https://ai.google.dev/gemini-api/docs/data-logging") }) { Text(uiText("Данные")) }
        TextButton(onClick = { uri.openUri(if (router) "https://openrouter.ai/docs/guides/routing/model-variants/free" else "https://ai.google.dev/gemini-api/docs/pricing") }) { Text(uiText("Лимиты")) }
    }
}
