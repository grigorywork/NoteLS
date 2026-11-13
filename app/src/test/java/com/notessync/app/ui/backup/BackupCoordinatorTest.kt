package com.notessync.app.ui.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import com.notessync.app.data.backup.BackupManager
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.data.local.entity.NoteEntity
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BackupCoordinatorTest {
    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val db get() = AppDatabase.get(app)
    private val password get() = "coordinator password123".toCharArray()
    private lateinit var scope: CoroutineScope
    private lateinit var owner: BackupCoordinator
    private lateinit var file: File
    private var id = 0L
    @Before fun before() = runBlocking {
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        app.getSharedPreferences("backup_security", 0).edit().clear().commit()
        app.getSharedPreferences("backup_biometrics", 0).edit().clear().commit()
        BackupManager.configureProtection(app, null, password)
        id = db.noteDao().upsert(NoteEntity(title = "Archived"))
        file = File(app.cacheDir, "coordinator.nlsbackup")
        file.outputStream().use { BackupManager.createBackup(app, it) }
        db.noteDao().update(db.noteDao().getById(id)!!.copy(title = "Current"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        owner = BackupCoordinator(app, scope, SettingsRepository.get(app), SnackbarHostState())
    }
    @After fun after() { owner.stopped(); scope.cancel(); AppDatabase.closeInstance() }
    private suspend fun idle() = withTimeout(15000) { while (owner.busy) delay(5) }
    @Test fun `correct password requires separate confirmation and cancellation preserves current notes`() = runBlocking {
        owner.select(Uri.fromFile(file)); idle()
        assertEquals(BackupPasswordMode.UNLOCK, owner.passwordMode); assertFalse(owner.confirmRestore)
        owner.password(BackupPasswordMode.UNLOCK, "incorrect".toCharArray(), charArrayOf()); idle()
        assertFalse(owner.confirmRestore); assertEquals("Current", db.noteDao().getById(id)!!.title)
        owner.password(BackupPasswordMode.UNLOCK, password, charArrayOf()); idle()
        assertTrue(owner.confirmRestore); assertEquals("Current", db.noteDao().getById(id)!!.title)
        owner.cancelRestore(); assertFalse(owner.confirmRestore); assertNull(owner.restoreInfo)
        assertEquals("Current", db.noteDao().getById(id)!!.title)
    }
    @Test fun `STOP during password derivation clears input and late result cannot open confirmation`() = runBlocking {
        owner.select(Uri.fromFile(file)); idle()
        val secret = password
        owner.password(BackupPasswordMode.UNLOCK, secret, charArrayOf())
        owner.stopped(); idle()
        assertTrue(secret.all { it == '\u0000' }); assertNull(owner.passwordMode); assertFalse(owner.confirmRestore)
        owner.started(); assertFalse(owner.confirmRestore)
        assertEquals("Current", db.noteDao().getById(id)!!.title)
    }
    @Test fun `late password callback after dismissal is wiped and cannot reopen restore`() = runBlocking {
        owner.select(Uri.fromFile(file)); idle(); owner.cancelRestore()
        val secret = password
        owner.password(BackupPasswordMode.UNLOCK, secret, charArrayOf()); idle()
        assertTrue(secret.all { it == '\u0000' }); assertFalse(owner.confirmRestore)
        assertEquals("Current", db.noteDao().getById(id)!!.title)
    }
    @Test fun `accepted restore owns its unlock through STOP and restores only after confirmation`() = runBlocking {
        owner.select(Uri.fromFile(file)); idle()
        owner.password(BackupPasswordMode.UNLOCK, password, charArrayOf()); idle()
        assertTrue(owner.confirmRestore)
        owner.restore(); owner.stopped(); idle()
        assertEquals("Archived", db.noteDao().getById(id)!!.title)
    }
}
