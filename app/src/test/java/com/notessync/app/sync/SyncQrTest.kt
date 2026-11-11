package com.notessync.app.sync

import com.notessync.app.sync.qr.SyncQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncQrTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `код туда-обратно`() {
        val raw = SyncQr.encode("192.168.1.5", 47383, "ABCD2345", now)
        val result = SyncQr.decode(raw, now + 10_000)
        assertTrue(result is SyncQr.DecodeResult.Ok)
        val payload = (result as SyncQr.DecodeResult.Ok).payload
        assertEquals("192.168.1.5", payload.ip)
        assertEquals(47383, payload.port)
        assertEquals("ABCD2345", payload.code)
    }

    @Test
    fun `просроченный код отклоняется`() {
        val raw = SyncQr.encode("192.168.1.5", 47383, "ABCD2345", now)
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
}
