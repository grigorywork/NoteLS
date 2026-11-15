package com.notessync.app.data.quotes

import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** File parsing and identities do not depend on Android, Room or the UI. */
data class QuoteDraft(val text: String, val author: String = "", val source: String = "") {
    fun normalized(): QuoteDraft {
        val result = copy(text = text.replace("\r\n", "\n").replace('\r', '\n').trim(), author = author.trim(), source = source.trim())
        fun unicode(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c.isHighSurrogate()) { if (i >= value.length || !value[i++].isLowSurrogate()) return false }
                else if (c.isLowSurrogate()) return false
            }
            return true
        }
        if (!listOf(result.text, result.author, result.source).all(::unicode) || result.text.isBlank() || result.text.length > 3000 || result.author.length > 160 || result.source.length > 240 ||
            result.text.any { it.isISOControl() && it != '\n' && it != '\t' } ||
            (result.author + result.source).any { it.isISOControl() }) throw QuoteException(QuoteProblem.INVALID)
        return result
    }
    fun fingerprint(): String {
        fun part(value: String) = Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace(Regex("[\\s\\p{Z}]+"), " ").trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(part(text)); digest.update(0.toByte()); digest.update(part(author))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun formatted(): String = "«$text»" + listOf(author, source).filter { it.isNotBlank() }.joinToString(", ").let {
        if (it.isBlank()) "" else "\n\n— $it"
    }
}
enum class QuoteProblem { INVALID, FILE_TOO_LARGE, TOO_MANY, LIMIT, DUPLICATE, STALE, FILE, SAVE }
class QuoteException(val problem: QuoteProblem) : Exception(problem.name)

object QuoteImport {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ENTRIES = 500
    const val MAX_LIBRARY = 2000
    suspend fun readCancellable(open: () -> InputStream?): List<QuoteDraft> = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val input = try { open() ?: throw QuoteException(QuoteProblem.FILE) } catch (_: Exception) {
                continuation.resumeWithException(QuoteException(QuoteProblem.FILE)); return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { runCatching { input.close() } }
            try {
                val values = input.use(::read)
                if (continuation.isActive) continuation.resume(values)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(if (error is QuoteException) error else QuoteException(QuoteProblem.FILE))
            }
        }
    }
    fun read(input: InputStream): List<QuoteDraft> {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - out.size() + 1))
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) throw QuoteException(QuoteProblem.FILE_TOO_LARGE)
            out.write(buffer, 0, n)
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) { throw QuoteException(QuoteProblem.INVALID) }
        if (text.isBlank()) throw QuoteException(QuoteProblem.INVALID)
        return if (text.trimStart().startsWith("[") || text.trimStart().startsWith("{")) json(text) else lines(text)
    }
    private fun json(text: String): List<QuoteDraft> {
        // Reject excessive nesting before JSON parsing, ignoring braces inside quoted strings.
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '[', '{' -> { depth++; if (depth > 8) throw QuoteException(QuoteProblem.INVALID) }; ']', '}' -> depth-- }
        }
        try {
            val entries = Json.parseToJsonElement(text) as? JsonArray ?: throw QuoteException(QuoteProblem.INVALID)
            if (entries.isEmpty()) throw QuoteException(QuoteProblem.INVALID)
            if (entries.size > MAX_ENTRIES) throw QuoteException(QuoteProblem.TOO_MANY)
            return entries.map { item ->
                val obj = item as? JsonObject ?: throw QuoteException(QuoteProblem.INVALID)
                if (obj.keys.any { it !in setOf("text", "author", "source") }) throw QuoteException(QuoteProblem.INVALID)
                fun value(key: String, required: Boolean = false): String {
                    val value = obj[key]
                    if (!required && (value == null || value == JsonNull)) return ""
                    val primitive = value as? JsonPrimitive ?: throw QuoteException(QuoteProblem.INVALID)
                    if (!primitive.isString) throw QuoteException(QuoteProblem.INVALID)
                    return primitive.content
                }
                QuoteDraft(value("text", true), value("author"), value("source")).normalized()
            }
        } catch (error: Exception) { throw if (error is QuoteException) error else QuoteException(QuoteProblem.INVALID) }
    }
    private fun lines(text: String): List<QuoteDraft> {
        val result = mutableListOf<QuoteDraft>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            if (result.size >= MAX_ENTRIES) throw QuoteException(QuoteProblem.TOO_MANY)
            val columns = line.split('\t')
            if (columns.size > 3) throw QuoteException(QuoteProblem.INVALID)
            result.add(QuoteDraft(columns[0], columns.getOrElse(1) { "" }, columns.getOrElse(2) { "" }).normalized())
        }
        if (result.isEmpty()) throw QuoteException(QuoteProblem.INVALID)
        return result
    }
}
