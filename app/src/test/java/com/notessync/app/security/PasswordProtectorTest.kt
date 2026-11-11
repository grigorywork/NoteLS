package com.notessync.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordProtectorTest {
    @Test
    fun correctPasswordPassesAndWrongPasswordFails() {
        val record = PasswordProtector.create("correct-horse")

        assertTrue(PasswordProtector.verify("correct-horse", record))
        assertFalse(PasswordProtector.verify("wrong", record))
    }

    @Test
    fun equalPasswordsUseDifferentSaltsAndHashes() {
        val first = PasswordProtector.create("same-password")
        val second = PasswordProtector.create("same-password")

        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
        assertTrue(PasswordProtector.verify("same-password", first))
        assertTrue(PasswordProtector.verify("same-password", second))
    }

    @Test
    fun malformedRecordFailsClosed() {
        val record = PasswordRecord(hash = "invalid", salt = "invalid")

        assertFalse(PasswordProtector.verify("password", record))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPasswordIsRejected() {
        PasswordProtector.create("123")
    }
}
