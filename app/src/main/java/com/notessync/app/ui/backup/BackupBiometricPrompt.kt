package com.notessync.app.ui.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.notessync.app.data.backup.crypto.BackupBiometricVault
import com.notessync.app.i18n.uiText

private tailrec fun Context.activity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
    else -> null
}
internal fun backupBiometricsAvailable(context: Context) = runCatching {
    BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
}.getOrDefault(false)

@Composable
internal fun BackupBiometricPrompt(request: BackupBiometricVault.Challenge, owner: BackupCoordinator) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(request, context, lifecycle) {
        var active = true
        var delivered = false
        var prompt: BiometricPrompt? = null
        val activity = context.activity()
        if (activity == null || !backupBiometricsAvailable(context) || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            owner.cancelBiometric(request)
        } else {
            prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!active || delivered || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                    val cipher = result.cryptoObject?.cipher
                    if (cipher == null) owner.cancelBiometric(request)
                    else { delivered = true; owner.authenticated(request, cipher) }
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (active && !delivered) owner.cancelBiometric(request)
                }
            })
            val info = BiometricPrompt.PromptInfo.Builder().setTitle(uiText("Защита резервной копии"))
                .setSubtitle(uiText("Отпечаток или сильная биометрия телефона"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(uiText("Пароль")).build()
            try { prompt.authenticate(info, BiometricPrompt.CryptoObject(request.cipher)) }
            catch (_: Exception) { owner.cancelBiometric(request) }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { active = false; prompt?.cancelAuthentication(); owner.cancelBiometric(request) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            active = false; lifecycle.removeObserver(observer); prompt?.cancelAuthentication()
            if (!delivered) owner.cancelBiometric(request)
        }
    }
}
