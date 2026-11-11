package com.notessync.app.sync

import com.notessync.app.sync.crypto.SessionCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException

class SessionCryptoTest {

    @Test
    fun `шифрование-расшифровка возвращает исходные данные`() {
        val crypto = SessionCrypto("SESSION1")
        val plain = "Заметка с русским текстом и emoji".toByteArray()
        assertArrayEquals(plain, crypto.decrypt(crypto.encrypt(plain)))
    }

    @Test
    fun `шифротекст не повторяется для одинаковых данных`() {
        val crypto = SessionCrypto("SESSION1")
        val plain = ByteArray(64) { 7 }
        assertFalse(crypto.encrypt(plain).contentEquals(crypto.encrypt(plain)))
    }

    @Test
    fun `чужой код сессии не расшифровывает данные`() {
        val sender = SessionCrypto("AAAAAAAA")
        val spy = SessionCrypto("BBBBBBBB")
        assertThrows(AEADBadTagException::class.java) {
            spy.decrypt(sender.encrypt("секрет".toByteArray()))
        }
    }

    @Test
    fun `подменённый байт обнаруживается`() {
        val crypto = SessionCrypto("SESSION1")
        val data = crypto.encrypt("важные данные".toByteArray())
        data[data.size / 2] = (data[data.size / 2].toInt() xor 0xFF).toByte()
        assertThrows(AEADBadTagException::class.java) { crypto.decrypt(data) }
    }
}
