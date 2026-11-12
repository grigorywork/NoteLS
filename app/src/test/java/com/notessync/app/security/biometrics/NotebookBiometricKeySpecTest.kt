package com.notessync.app.security.biometrics

import android.app.Application
import android.content.Context
import android.security.keystore.KeyProperties
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Platform key policy only; no real device Keystore or sensor is exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class NotebookBiometricKeySpecTest {
    private val alias = "notels.biometric.11111111-1111-1111-1111-111111111111"
    @Test fun `modern key requires per use strong biometric and invalidates on enrollment changes`() {
        val spec = AndroidNotebookBiometricVault(RuntimeEnvironment.getApplication()).keySpec(alias)
        assertTrue(spec.isUserAuthenticationRequired); assertEquals(0, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, spec.userAuthenticationType)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
        assertArrayEquals(arrayOf(KeyProperties.BLOCK_MODE_GCM), spec.blockModes)
        assertArrayEquals(arrayOf(KeyProperties.ENCRYPTION_PADDING_NONE), spec.encryptionPaddings)
        assertEquals(256, spec.keySize)
        assertTrue(runCatching { AndroidNotebookBiometricVault(RuntimeEnvironment.getApplication()).keySpec("release") }.isFailure)
    }
    @Test @Config(sdk = [28]) fun `legacy key never allows a cached device credential session`() {
        val spec = AndroidNotebookBiometricVault(RuntimeEnvironment.getApplication()).keySpec(alias)
        assertTrue(spec.isUserAuthenticationRequired); assertEquals(-1, spec.userAuthenticationValidityDurationSeconds)
        assertTrue(spec.isInvalidatedByBiometricEnrollment)
    }
    @Test fun `corrupt local enrollment metadata cannot grant permission when loading`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("notebook_biometrics", Context.MODE_PRIVATE).edit().putString("corrupt", "{invalid").commit()
        val vault = AndroidNotebookBiometricVault(app)
        vault.load(); assertTrue(vault.bindings.value.isEmpty())
    }
}
