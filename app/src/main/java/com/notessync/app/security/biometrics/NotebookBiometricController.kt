package com.notessync.app.security.biometrics

import androidx.room.withTransaction
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.PasswordProtector
import com.notessync.app.security.PasswordRecord
import com.notessync.app.security.ProtectionSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.Cipher

/** One foreground operation, consumed once. Stale password snapshots and late callbacks grant no access. */
class NotebookBiometricController(private val scope: CoroutineScope, private val db: AppDatabase,
    private val vault: NotebookBiometricVault) {
    enum class PrepareResult { READY, PASSWORD, UNAVAILABLE, STALE }
    private class WrongPassword : Exception()
    private class StaleNotebook : Exception()
    data class Request(val token: Long, val notebookId: Long, val challenge: BiometricChallenge)
    @Volatile private var generation = 0L
    private val ready = scope.async { vault.load() }
    private val _request = MutableStateFlow<Request?>(null)
    val request = _request.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val bindings = vault.bindings

    fun unlockPassword(notebook: NotebookEntity, password: String, onResult: (Boolean) -> Unit) {
        cancel()
        val token = generation
        _busy.value = true
        scope.launch {
            var accepted = false
            try {
                val fresh = db.notebookDao().getById(notebook.id)
                val binding = fresh?.let { NotebookBiometricBinding.from(it) }
                val verified = binding != null && fresh.syncId == notebook.syncId && withContext(Dispatchers.Default) {
                    PasswordProtector.verify(password, PasswordRecord(fresh.passwordHash!!, fresh.passwordSalt!!))
                }
                accepted = db.withTransaction {
                    val unchanged = binding != null && matches(notebook.id, binding)
                    withContext(Dispatchers.Main.immediate) {
                        val valid = verified && unchanged && generation == token
                        if (valid) ProtectionSession.unlockNotebook(notebook.id)
                        valid
                    }
                }
            } catch (error: Exception) { if (error is CancellationException) throw error }
            finally { if (generation == token) { _busy.value = false; onResult(accepted) } }
        }
    }

    fun prepare(notebook: NotebookEntity, enroll: Boolean, password: String = "", onReady: (PrepareResult) -> Unit) {
        cancel()
        val token = generation
        _busy.value = true
        scope.launch {
            var challenge: BiometricChallenge? = null
            try {
                ready.await()
                val fresh = db.notebookDao().getById(notebook.id)
                val binding = fresh?.let { NotebookBiometricBinding.from(it) }
                if (binding == null || fresh.syncId != notebook.syncId || binding != NotebookBiometricBinding.from(notebook)) throw StaleNotebook()
                if (enroll) {
                    val verified = withContext(Dispatchers.Default) {
                        PasswordProtector.verify(password, PasswordRecord(fresh.passwordHash!!, fresh.passwordSalt!!))
                    }
                    if (!verified) throw WrongPassword()
                }
                check(generation == token && matches(notebook.id, binding))
                challenge = vault.prepare(binding, enroll)
                if (generation != token || !matches(notebook.id, binding)) return@launch
                _request.value = Request(token, notebook.id, challenge)
                challenge = null // The pending request owns cleanup now.
                onReady(PrepareResult.READY)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == token) onReady(when (error) { is WrongPassword -> PrepareResult.PASSWORD; is StaleNotebook -> PrepareResult.STALE; else -> PrepareResult.UNAVAILABLE })
            } finally {
                challenge?.let { unused -> withContext(NonCancellable) { runCatching { vault.discard(unused) } } }
                if (generation == token) _busy.value = false
            }
        }
    }

    fun authenticate(request: Request, authenticated: Cipher, onResult: (Boolean) -> Unit) {
        if (_request.value !== request || request.token != generation) return
        _request.value = null // Consume before any suspension; disposing the prompt cannot reuse it.
        _busy.value = true
        scope.launch {
            var accepted = false
            try {
                val binding = request.challenge.binding
                if (generation == request.token && matches(request.notebookId, binding)) {
                    val cryptoVerified = vault.verify(request.challenge, authenticated)
                    accepted = db.withTransaction {
                        val fresh = matches(request.notebookId, binding)
                        withContext(Dispatchers.Main.immediate) {
                            val valid = cryptoVerified && fresh && generation == request.token
                            if (valid && !request.challenge.enroll) ProtectionSession.unlockNotebook(request.notebookId)
                            valid
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                if (!accepted) withContext(NonCancellable) { runCatching { vault.discard(request.challenge) } }
                if (generation == request.token) {
                    _busy.value = false
                    onResult(accepted)
                }
            }
        }
    }

    fun cancelIfPending(request: Request) { if (_request.value === request) cancel() }
    fun cancel() {
        generation++
        val old = _request.value
        _request.value = null; _busy.value = false
        if (old != null) scope.launch(NonCancellable) { runCatching { vault.discard(old.challenge) } }
    }

    fun revoke(syncId: String, onResult: (Boolean) -> Unit) {
        cancel()
        scope.launch {
            val success = runCatching { ready.await(); vault.revoke(syncId) }.isSuccess
            onResult(success)
        }
    }
    private suspend fun matches(id: Long, binding: NotebookBiometricBinding): Boolean =
        db.notebookDao().getById(id)?.let { NotebookBiometricBinding.from(it) } == binding
}
