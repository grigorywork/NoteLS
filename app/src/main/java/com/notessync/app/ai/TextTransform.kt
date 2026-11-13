package com.notessync.app.ai

import com.notessync.app.i18n.uiText
import com.notessync.app.voice.TextStructurer

enum class AiProvider(private val text: String) {
    LOCAL_RULES("Без сети (правила)"), OPENAI("OpenAI"); val label get() = uiText(text)
}
enum class TransformMode(private val text: String) {
    TEXT("Связный текст"), LIST("Список"); val label get() = uiText(text)
}
data class TransformConfig(val enabled: Boolean = false, val provider: AiProvider = AiProvider.LOCAL_RULES, val model: String = "")
enum class TransformProblem { DISABLED, EMPTY, TOO_LARGE, KEY_MISSING, KEY_UNAVAILABLE, MODEL_MISSING, STALE, ACCESS,
    NETWORK, TIMEOUT, UNAUTHORIZED, RATE_LIMIT, PROVIDER, INCOMPLETE, REFUSED, SAVE }
class TransformException(val problem: TransformProblem) : Exception(problem.name)
object TransformLimits {
    const val INPUT = 16_000
    const val OUTPUT = 32_000
    const val RESPONSE_BYTES = 65_536
    private val breaks = Regex("(?<=[.!?…;])\\s+|\\r?\\n+")
    private val modelPattern = Regex("[A-Za-z0-9._:/-]{1,160}")
    fun validModel(value: String) = modelPattern.matches(value)
    fun validKey(value: String) = value.length in 1..4096 && value.all { it.code in 33..126 }
    fun input(text: String) {
        if (text.isBlank()) throw TransformException(TransformProblem.EMPTY)
        if (text.length > INPUT) throw TransformException(TransformProblem.TOO_LARGE)
    }
    fun output(text: String): String {
        if (text.isBlank()) throw TransformException(TransformProblem.PROVIDER)
        if (text.length > OUTPUT) throw TransformException(TransformProblem.TOO_LARGE)
        return text
    }
    fun local(text: String, mode: TransformMode): String {
        input(text)
        return output(if (mode == TransformMode.LIST) breaks.split(text).asSequence().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n") { if (it.startsWith("- ")) it else "- $it" } else TextStructurer.normalize(text))
    }
}
interface TransformSecrets { suspend fun read(): String?; suspend fun save(value: String); suspend fun clear(); suspend fun present(): Boolean }
interface TransformTransport { suspend fun transform(key: String, model: String, input: String, mode: TransformMode): String }
