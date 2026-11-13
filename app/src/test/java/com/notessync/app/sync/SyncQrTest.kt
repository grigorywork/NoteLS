package com.notessync.app.sync

import com.notessync.app.sync.qr.SyncQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncQrTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `код туда-обратно`() {
        val raw = SyncQr.encode("192.168.1.5", 47383, "ab".repeat(32), now)
        val result = SyncQr.decode(raw, now + 10_000)
        assertTrue(result is SyncQr.DecodeResult.Ok)
        val payload = (result as SyncQr.DecodeResult.Ok).payload
        assertEquals("192.168.1.5", payload.ip)
        assertEquals(47383, payload.port)
        assertEquals("ab".repeat(32), payload.code)
    }

    @Test
    fun `просроченный код отклоняется`() {
        val raw = SyncQr.encode("192.168.1.5", 47383, "ab".repeat(32), now)
        val result = SyncQr.decode(raw, now + SyncQr.TTL_MS + 1)
        assertTrue(result is SyncQr.DecodeResult.Invalid)
    }

    @Test
    fun `чужой код отклоняется`() {
        assertTrue(SyncQr.decode("https://example.com/evil", now) is SyncQr.DecodeResult.Invalid)
        assertTrue(SyncQr.decode("любой текст", now) is SyncQr.DecodeResult.Invalid)
    }

    @Test
    fun `код без полей подключения отклоняется`() {
        val raw = SyncQr.SCHEME + "?ip=&port=&code=&ts=$now"
        assertTrue(SyncQr.decode(raw, now) is SyncQr.DecodeResult.Invalid)
    }

    @Test fun `duplicate unknown fragment oversized and legacy parameters are refused`() {
        val raw = SyncQr.encode("192.168.1.5", SyncQr.PORT, "ab".repeat(32), now)
        listOf(raw + "&ip=10.0.0.2", raw + "&extra=x", raw + "#fragment", raw + "&" + "x".repeat(512),
            raw.replace("&v=3", ""), raw.replace("&v=3", "&v=2"), raw.replace("ab".repeat(32), "ABCD2345"))
            .forEach { assertTrue(it, SyncQr.decode(it, now) is SyncQr.DecodeResult.Invalid) }
    }
    @Test fun `QR cannot cause DNS public loopback malformed or alternate port connections`() {
        val raw = SyncQr.encode("192.168.1.5", SyncQr.PORT, "ab".repeat(32), now)
        listOf("localhost", "example.com", "127.0.0.1", "8.8.8.8", "224.1.1.1", "::1", "192.168.01.5", "192.168.1.999", "10.0.0.1%00")
            .forEach { assertTrue(it, SyncQr.decode(raw.replace("192.168.1.5", it), now) is SyncQr.DecodeResult.Invalid) }
        listOf("0", "65536", "80", "047383", "-1").forEach {
            assertTrue(SyncQr.decode(raw.replace("port=47383", "port=$it"), now) is SyncQr.DecodeResult.Invalid)
        }
        listOf("10.0.0.1", "172.16.0.2", "172.31.255.1", "192.168.0.1", "169.254.1.2").forEach { assertTrue(SyncQr.localAddress(it)) }
    }
    @Test fun `timestamp TTL future allowance and overflow are bounded`() {
        val raw = SyncQr.encode("192.168.1.5", SyncQr.PORT, "ab".repeat(32), now)
        assertTrue(SyncQr.decode(raw, now + SyncQr.TTL_MS) is SyncQr.DecodeResult.Ok)
        assertTrue(SyncQr.decode(raw, now - 60_000) is SyncQr.DecodeResult.Ok)
        assertTrue(SyncQr.decode(raw, now - 60_001) is SyncQr.DecodeResult.Invalid)
        listOf(Long.MIN_VALUE.toString(), Long.MAX_VALUE.toString(), "9999999999999999999999").forEach {
            assertTrue(SyncQr.decode(raw.replace("ts=$now", "ts=$it"), now) is SyncQr.DecodeResult.Invalid)
        }
    }
}
