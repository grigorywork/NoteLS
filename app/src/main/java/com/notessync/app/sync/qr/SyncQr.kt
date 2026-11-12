package com.notessync.app.sync.qr

import com.notessync.app.i18n.uiText

/**
 * Полезная нагрузка QR-кода сопряжения (Этап 4):
 * notessync://connect?ip=<ip>&port=<port>&code=<код>&ts=<мс>
 * Код действует 5 минут; чужие (не нашей схемы) и просроченные отклоняются.
 */
object SyncQr {

    const val SCHEME = "notessync://connect"
    const val TTL_MS = 5 * 60 * 1000L

    data class Payload(val ip: String, val port: Int, val code: String)

    fun encode(ip: String, port: Int, code: String, now: Long = System.currentTimeMillis()): String =
        "$SCHEME?ip=$ip&port=$port&code=$code&ts=$now"

    sealed interface DecodeResult {
        data class Ok(val payload: Payload) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    fun decode(raw: String, now: Long = System.currentTimeMillis()): DecodeResult {
        if (!raw.startsWith("$SCHEME?")) return DecodeResult.Invalid(uiText("Это не код приложения NoteLS"))
        val params = raw.removePrefix("$SCHEME?")
            .split('&')
            .mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
            }
            .toMap()

        val ip = params["ip"].orEmpty()
        val port = params["port"]?.toIntOrNull()
        val code = params["code"].orEmpty()
        val ts = params["ts"]?.toLongOrNull()

        if (ip.isBlank() || port == null || code.length != 8) {
            return DecodeResult.Invalid(uiText("В коде нет данных подключения"))
        }
        if (ts == null || now - ts > TTL_MS || ts > now + 60_000) {
            return DecodeResult.Invalid(uiText("Код просрочен — сгенерируйте новый"))
        }
        return DecodeResult.Ok(Payload(ip, port, code))
    }
}
