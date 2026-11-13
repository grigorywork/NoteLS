package com.notessync.app.sync

import com.notessync.app.sync.crypto.Hkdf
import com.notessync.app.sync.crypto.SessionCrypto
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.AEADBadTagException

class SessionCryptoTest {
    private val root = ByteArray(32) { it.toByte() }
    private val header = byteArrayOf(1, 0, 0, 0, 42)
    private fun pair() = SessionCrypto.derive(root, true) to SessionCrypto.derive(root, false)

    @Test fun `RFC5869 test case one matches extract and multi-block expansion`() {
        fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val prk = Hkdf.extract(hex("000102030405060708090a0b0c"), ByteArray(22) { 0x0b })
        assertArrayEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        assertArrayEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            Hkdf.expand(prk, hex("f0f1f2f3f4f5f6f7f8f9"), 42))
        val emptySalt = Hkdf.extract(ByteArray(0), ByteArray(22) { 0x0b })
        assertArrayEquals(hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"), emptySalt)
        assertArrayEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            Hkdf.expand(emptySalt, ByteArray(0), 42))
    }
    @Test fun `directional encryption preserves UTF8 and independent simultaneous directions`() {
        val (client, server) = pair()
        val c = "Запись 👩🏽‍💻".toByteArray(); val s = "Ответ".toByteArray()
        val outbound = client.encrypt(c, header); val inbound = server.encrypt(s, header)
        assertArrayEquals(c, server.decrypt(outbound, header)); assertArrayEquals(s, client.decrypt(inbound, header))
    }
    @Test fun `repeated plaintext has unique counter ciphertext and replays are refused`() {
        val (client, server) = pair(); val plain = ByteArray(64) { 7 }
        val first = client.encrypt(plain, header); val next = client.encrypt(plain, header)
        assertFalse(first.contentEquals(next)); assertArrayEquals(plain, server.decrypt(first, header))
        assertThrows(IllegalArgumentException::class.java) { server.decrypt(first, header) }
        assertArrayEquals(plain, server.decrypt(next, header))
    }
    @Test fun `reorder gap and truncated frames are refused`() {
        val (client, server) = pair(); client.encrypt(byteArrayOf(1), header)
        val next = client.encrypt(byteArrayOf(2), header)
        assertThrows(IllegalArgumentException::class.java) { server.decrypt(next, header) }
        assertThrows(IllegalArgumentException::class.java) { server.decrypt(ByteArray(23), header) }
    }
    @Test fun `another connection and reflection cannot decrypt a frame`() {
        val (client, server) = pair(); val frame = client.encrypt(byteArrayOf(8), header)
        assertThrows(AEADBadTagException::class.java) { client.decrypt(frame, header) }
        val stranger = SessionCrypto.derive(ByteArray(32) { 99 }, false)
        assertThrows(AEADBadTagException::class.java) { stranger.decrypt(frame, header) }
        assertArrayEquals(byteArrayOf(8), server.decrypt(frame, header))
    }
    @Test fun `kind length and ciphertext tampering fail authentication without advancing receiver`() {
        val (client, server) = pair(); val frame = client.encrypt("secret".toByteArray(), header)
        assertThrows(AEADBadTagException::class.java) { server.decrypt(frame, header.copyOf().also { it[0] = 2 }) }
        assertThrows(AEADBadTagException::class.java) { server.decrypt(frame, header.copyOf().also { it[4] = 43 }) }
        assertThrows(AEADBadTagException::class.java) { server.decrypt(frame.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }, header) }
        assertArrayEquals("secret".toByteArray(), server.decrypt(frame, header))
    }
}
