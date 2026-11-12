package com.notessync.app.security.biometrics

import android.app.Application
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NotebookEntity
import com.notessync.app.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Software AES proves policy/AEAD handling, not Android Keystore or a real fingerprint sensor. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotebookBiometricControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private lateinit var db: AppDatabase
    private lateinit var book: NotebookEntity
    private lateinit var vault: SoftwareVault
    private lateinit var controller: NotebookBiometricController
    @Before fun before() = runBlocking {
        Dispatchers.setMain(dispatcher)
        val app = RuntimeEnvironment.getApplication()
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db"); db = AppDatabase.get(app)
        ProtectionSession.state.value.notebookIds.forEach { ProtectionSession.lockNotebook(it) }
        val password = PasswordProtector.create("owner-password")
        val initial = NotebookEntity(name = "Private", isProtected = true, passwordHash = password.hash, passwordSalt = password.salt)
        book = initial.copy(id = db.notebookDao().upsert(initial))
        vault = SoftwareVault(); controller = NotebookBiometricController(scope, db, vault)
    }
    @After fun after() {
        controller.cancel(); scope.cancel(); dispatcher.scheduler.runCurrent()
        AppDatabase.closeInstance(); Dispatchers.resetMain()
    }
    private suspend fun prepare(enroll: Boolean, password: String = "owner-password"): NotebookBiometricController.Request {
        val ready = CompletableDeferred<NotebookBiometricController.PrepareResult>()
        controller.prepare(book, enroll, password) { ready.complete(it) }
        assertEquals(NotebookBiometricController.PrepareResult.READY, ready.await())
        return controller.request.value!!
    }
    private suspend fun authenticate(request: NotebookBiometricController.Request, cipher: Cipher = request.challenge.cipher): Boolean {
        val done = CompletableDeferred<Boolean>()
        controller.authenticate(request, cipher) { done.complete(it) }
        return done.await()
    }
    private suspend fun enroll() { assertTrue(authenticate(prepare(true))); assertFalse(ProtectionSession.isUnlocked(book)) }

    @Test fun `explicit password enrollment then real software AEAD proof opens only that notebook`() = runTest(dispatcher) {
        enroll()
        assertEquals(NotebookBiometricBinding.from(book)!!.credentialTag, controller.bindings.value[book.syncId])
        assertTrue(authenticate(prepare(false)))
        assertTrue(ProtectionSession.isUnlocked(book))
        assertEquals(setOf(book.id), ProtectionSession.state.value.notebookIds)
    }
    @Test fun `wrong owner password never prepares an enrollment key`() = runTest(dispatcher) {
        val ready = CompletableDeferred<NotebookBiometricController.PrepareResult>()
        controller.prepare(book, true, "wrong") { ready.complete(it) }
        assertEquals(NotebookBiometricController.PrepareResult.PASSWORD, ready.await())
        assertEquals(0, vault.preparations); assertTrue(controller.bindings.value.isEmpty())
        assertFalse(ProtectionSession.isUnlocked(book))
    }
    @Test fun `arbitrary success cipher and repeated callback cannot unlock`() = runTest(dispatcher) {
        enroll(); val request = prepare(false)
        val other = Cipher.getInstance("AES/GCM/NoPadding")
        other.init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").generateKey())
        assertFalse(authenticate(request, other)); assertFalse(ProtectionSession.isUnlocked(book))
        var repeated = false
        controller.authenticate(request, request.challenge.cipher) { repeated = true }
        runCurrent(); assertFalse(repeated)
    }
    @Test fun `changed password invalidates prepared proof and enrollment binding`() = runTest(dispatcher) {
        enroll(); val request = prepare(false)
        val password = PasswordProtector.create("new-password")
        db.notebookDao().update(book.copy(passwordHash = password.hash, passwordSalt = password.salt))
        assertFalse(authenticate(request)); assertFalse(ProtectionSession.isUnlocked(book))
        assertNotEquals(controller.bindings.value[book.syncId], NotebookBiometricBinding.from(db.notebookDao().getById(book.id)!!)!!.credentialTag)
        val ready = CompletableDeferred<NotebookBiometricController.PrepareResult>()
        controller.prepare(book, true, "owner-password") { ready.complete(it) }
        assertEquals(NotebookBiometricController.PrepareResult.STALE, ready.await())
    }
    @Test fun `notebook deleted or replaced during authentication never opens`() = runTest(dispatcher) {
        enroll(); val request = prepare(false)
        vault.beforeVerify = { db.notebookDao().update(book.copy(isDeleted = true)) }
        assertFalse(authenticate(request)); assertFalse(ProtectionSession.isUnlocked(book))
    }
    @Test fun `stop during cryptographic verification rejects late success and password still works`() = runTest(dispatcher) {
        enroll(); val request = prepare(false)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        vault.beforeVerify = { entered.complete(Unit); release.await() }
        var callback = false
        controller.authenticate(request, request.challenge.cipher) { callback = true }
        entered.await(); controller.cancel(); release.complete(Unit)
        vault.discarded.await(); runCurrent()
        assertFalse(callback); assertFalse(ProtectionSession.isUnlocked(book))
        val fallback = CompletableDeferred<Boolean>()
        controller.unlockPassword(book, "owner-password") { fallback.complete(it) }
        assertTrue(fallback.await()); assertTrue(ProtectionSession.isUnlocked(book))
    }
    @Test fun `cancel while preparing enrollment cleans unused key without publishing request`() = runTest(dispatcher) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        vault.beforePrepare = { entered.complete(Unit); release.await() }
        var callback = false
        controller.prepare(book, true, "owner-password") { callback = true }
        entered.await(); controller.cancel(); release.complete(Unit)
        vault.discarded.await(); runCurrent()
        assertFalse(callback); assertNull(controller.request.value); assertTrue(controller.bindings.value.isEmpty())
    }
    @Test fun `missing sensor key or corrupt ciphertext grants no access and revoke removes permission`() = runTest(dispatcher) {
        enroll(); vault.available = false
        val ready = CompletableDeferred<NotebookBiometricController.PrepareResult>()
        controller.prepare(book, false) { ready.complete(it) }
        assertEquals(NotebookBiometricController.PrepareResult.UNAVAILABLE, ready.await())
        assertFalse(ProtectionSession.isUnlocked(book))
        vault.available = true; vault.corrupt = true
        assertFalse(authenticate(prepare(false))); assertFalse(ProtectionSession.isUnlocked(book))
        val revoked = CompletableDeferred<Boolean>(); controller.revoke(book.syncId) { revoked.complete(it) }
        assertTrue(revoked.await()); assertTrue(controller.bindings.value.isEmpty())
    }
    @Test fun `binding survives local id renumbering and rejects password salt identity or protection changes`() {
        val binding = NotebookBiometricBinding.from(book)
        assertEquals(binding, NotebookBiometricBinding.from(book.copy(id = 900)))
        assertNotEquals(binding, NotebookBiometricBinding.from(book.copy(syncId = "different")))
        assertNotEquals(binding, NotebookBiometricBinding.from(book.copy(passwordSalt = "different")))
        assertNull(NotebookBiometricBinding.from(book.copy(isProtected = false)))
        assertNull(NotebookBiometricBinding.from(book.copy(isDeleted = true)))
        assertNull(NotebookBiometricBinding.from(book.copy(passwordHash = null)))
    }

    @Test fun `backup restore clears access when local notebook ids are reused`() = runTest(dispatcher) {
        val app = RuntimeEnvironment.getApplication()
        val noteId = db.noteDao().upsert(com.notessync.app.data.local.entity.NoteEntity(title = "Private body", notebookId = book.id))
        val archive = java.io.ByteArrayOutputStream()
        com.notessync.app.data.backup.BackupManager.createBackup(app, archive)
        val file = java.io.File(app.cacheDir, "biometric-binding-backup.zip").apply { writeBytes(archive.toByteArray()) }
        db.notebookDao().update(book.copy(syncId = "different-notebook"))
        ProtectionSession.unlockNotebook(book.id); ProtectionSession.unlockTask("old-task")
        assertTrue(com.notessync.app.data.backup.BackupManager.restore(app, android.net.Uri.fromFile(file)).isSuccess)
        assertTrue(ProtectionSession.state.value.notebookIds.isEmpty()); assertTrue(ProtectionSession.state.value.taskSyncIds.isEmpty())
        assertTrue(visibleNotes(listOf(db.noteDao().getById(noteId)!!), listOf(db.notebookDao().getById(book.id)!!), ProtectionSession.state.value).isEmpty())
    }

    private class SoftwareVault : NotebookBiometricVault {
        override val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private var iv = byteArrayOf(); private var data = byteArrayOf()
        var beforePrepare: suspend () -> Unit = {}; var beforeVerify: suspend () -> Unit = {}
        val discarded = CompletableDeferred<Unit>()
        var preparations = 0; var available = true; var corrupt = false
        override suspend fun load() {}
        override suspend fun prepare(binding: NotebookBiometricBinding, enroll: Boolean): BiometricChallenge {
            preparations++; beforePrepare(); check(available)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (enroll) cipher.init(Cipher.ENCRYPT_MODE, key)
            else {
                check(bindings.value[binding.syncId] == binding.credentialTag)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            }
            cipher.updateAAD(binding.credentialTag.toByteArray())
            val bytes = data.copyOf().also { if (corrupt && it.isNotEmpty()) it[0] = (it[0].toInt() xor 1).toByte() }
            return BiometricChallenge(binding, enroll, cipher, "test-software", bytes)
        }
        override suspend fun verify(challenge: BiometricChallenge, cipher: Cipher): Boolean {
            beforeVerify()
            if (!challenge.consume(cipher)) return false
            return if (challenge.enroll) {
                data = cipher.doFinal("permission".toByteArray()); iv = cipher.iv
                bindings.value = mapOf(challenge.binding.syncId to challenge.binding.credentialTag); true
            } else runCatching { cipher.doFinal(challenge.ciphertext).contentEquals("permission".toByteArray()) }.getOrDefault(false)
        }
        override suspend fun discard(challenge: BiometricChallenge) {
            if (challenge.enroll) bindings.value = emptyMap()
            discarded.complete(Unit)
        }
        override suspend fun revoke(syncId: String) { bindings.value = emptyMap() }
    }
}
