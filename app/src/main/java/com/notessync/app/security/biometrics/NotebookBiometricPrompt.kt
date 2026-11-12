package com.notessync.app.security.biometrics

import com.notessync.app.i18n.uiText

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
import javax.crypto.Cipher

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.fragmentActivity() else null
    else -> null
}

fun canUseNotebookBiometrics(context: Context): Boolean = runCatching { BiometricManager.from(context)
    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS }.getOrDefault(false)

/** Native prompt only supplies an authorized CryptoObject; the controller still verifies bytes and fresh DB state. */
@Composable
fun NotebookBiometricPrompt(request: NotebookBiometricController.Request,
    onAuthenticated: (Cipher) -> Unit, onFallback: () -> Unit, onDisposed: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val authenticated by rememberUpdatedState(onAuthenticated)
    val fallback by rememberUpdatedState(onFallback)
    val disposed by rememberUpdatedState(onDisposed)
    DisposableEffect(request, context, lifecycle) {
        var active = true
        val activity = context.fragmentActivity()
        var prompt: BiometricPrompt? = null
        if (activity == null || !canUseNotebookBiometrics(context) || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            fallback()
        } else {
            prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!active || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
                    val cipher = result.cryptoObject?.cipher
                    if (cipher == null) fallback() else authenticated(cipher)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (active) fallback()
                }
                override fun onAuthenticationFailed() { /* Native prompt allows another attempt. */ }
            })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(if (request.challenge.enroll) uiText("Включить биометрию блокнота") else uiText("Открыть блокнот"))
                .setSubtitle(uiText("Отпечаток или сильная биометрия телефона"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(uiText("Пароль"))
                .build()
            try { prompt.authenticate(info, BiometricPrompt.CryptoObject(request.challenge.cipher)) }
            catch (_: Exception) { fallback() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                active = false
                prompt?.cancelAuthentication()
                disposed()
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            active = false
            prompt?.cancelAuthentication()
            disposed()
        }
    }
}
