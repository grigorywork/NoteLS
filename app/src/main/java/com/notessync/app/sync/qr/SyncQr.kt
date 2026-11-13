package com.notessync.app.sync.qr

import com.notessync.app.i18n.uiText
import com.notessync.app.sync.crypto.PairingHandshake
import com.notessync.app.sync.crypto.PairingSecret

/** Literal LAN IPv4 only; no DNS resolution, duplicate parameters, or weak legacy invitations. */
object SyncQr {
    const val SCHEME = "notessync://connect"
    const val PORT = 47383
    const val TTL_MS = 5 * 60 * 1000L
    private val fields = setOf("ip", "port", "code", "ts", "v")
    private val octet = Regex("0|[1-9][0-9]{0,2}")
    data class Payload(val ip: String, val port: Int, val code: String)

    fun localAddress(ip: String): Boolean {
        if (ip.length > 15) return false
        val parts = ip.split('.')
        if (parts.size != 4 || parts.any { !octet.matches(it) }) return false
        val n = parts.map { it.toInt() }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 10 || (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 192 && n[1] == 168) || (n[0] == 169 && n[1] == 254)
    }

    fun encode(ip: String, port: Int, code: String, now: Long = System.currentTimeMillis()): String {
        require(localAddress(ip) && port == PORT && PairingSecret.valid(code) && now >= 0)
        return "$SCHEME?ip=$ip&port=$port&code=$code&ts=$now&v=${PairingHandshake.VERSION}"
    }
    sealed interface DecodeResult {
        data class Ok(val payload: Payload) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }
    fun decode(raw: String, now: Long = System.currentTimeMillis()): DecodeResult {
        fun invalid() = DecodeResult.Invalid(uiText("Неверный QR-код. Обновите NoteLS на обоих устройствах и создайте новый код."))
        if (raw.length > 512 || !raw.startsWith("$SCHEME?") || '#' in raw) return invalid()
        val params = mutableMapOf<String, String>()
        for (part in raw.substring(SCHEME.length + 1).split('&')) {
            val index = part.indexOf('=')
            if (index <= 0) return invalid()
            val name = part.substring(0, index)
            if (name !in fields || params.put(name, part.substring(index + 1)) != null) return invalid()
        }
        if (params.keys != fields || params["v"] != PairingHandshake.VERSION.toString() || params["port"] != PORT.toString()) return invalid()
        val ip = params.getValue("ip")
        val code = params.getValue("code")
        if (!localAddress(ip) || !PairingSecret.valid(code)) return invalid()
        val ts = params["ts"]?.toLongOrNull() ?: return invalid()
        if (now < 0 || ts < 0 || (ts <= now && now - ts > TTL_MS) || (ts > now && ts - now > 60_000)) {
            return DecodeResult.Invalid(uiText("Код просрочен — сгенерируйте новый"))
        }
        return DecodeResult.Ok(Payload(ip, PORT, code))
    }
}
