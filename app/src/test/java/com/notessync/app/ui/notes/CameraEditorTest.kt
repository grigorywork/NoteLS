package com.notessync.app.ui.notes

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.notessync.app.data.attachments.CameraCaptureStore
import com.notessync.app.data.attachments.registerCameraTestProvider
import com.notessync.app.data.local.AppDatabase
import com.notessync.app.settings.BackupFrequency
import com.notessync.app.settings.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE, shadows = [CameraBoundsShadow::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CameraEditorTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var app: Application
    private lateinit var vm: NoteEditorViewModel
    private lateinit var state: SavedStateHandle
    @Before fun before() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication()
        registerCameraTestProvider(app)
        AppDatabase.closeInstance(); app.deleteDatabase("notessync.db")
        SettingsRepository.get(app).setBackupFrequency(BackupFrequency.MANUAL)
        state = SavedStateHandle()
        vm = NoteEditorViewModel(app, state); models.put("editor", vm)
        dispatcher.scheduler.runCurrent()
        assertTrue("Editor not ready before capture", vm.isReady)
    }
    @After fun after() { models.clear(); dispatcher.scheduler.runCurrent(); AppDatabase.closeInstance(); Dispatchers.resetMain() }
    private fun writePhoto(token: String): ByteArray {
        val f = CameraCaptureStore(app).file(token)
        f.writeBytes(java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAYEBQYFBAYGBQYHBwYIChAKCgkJChQODwwQFxQYGBcUFhYaHSUfGhsjHBYWICwgIyYnKSopGR8tMC0oMCUoKSj/2wBDAQcHBwoIChMKChMoGhYaKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCgoKCj/wAARCABgAIADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDLooor3zxQooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKACiiigAooooAKKKKAP/2Q=="))
        return f.readBytes()
    }

    @Test fun `capture restores its note and token and callback attaches once after editor recreation`() = runTest(dispatcher) {
        vm.onTitleChange(androidx.compose.ui.text.input.TextFieldValue("Camera note"))
        assertNotNull(vm.prepareCameraCapture())
        val noteId = vm.currentNoteId
        val token = state.get<String>("cameraCapture")!!
        val original = writePhoto(token)
        val restored = SavedStateHandle(mapOf("noteId" to state.get<Long>("noteId"),
            "cameraCapture" to token, "createdHere" to true))
        vm = NoteEditorViewModel(app, restored); models.put("editor", vm)
        // Deliver before asynchronous Room load completes, as ActivityResultRegistry may do.
        vm.finishCameraCapture(true).join()
        vm.finishCameraCapture(true).join()
        val db = AppDatabase.get(app)
        assertEquals(noteId, vm.currentNoteId)
        assertEquals("Camera note", db.noteDao().getById(noteId)!!.title)
        val photo = db.attachmentDao().getByNoteId(noteId).single()
        assertArrayEquals(original, File(photo.filePath).readBytes())
        assertFalse(CameraCaptureStore(app).file(token).exists())
        assertNull(restored.get<String>("cameraCapture")); assertFalse(vm.isPhotoBusy)
        assertTrue(vm.flushOnExit()); assertFalse(db.noteDao().getById(noteId)!!.isDeleted)
    }

    @Test fun `cancel removes even written output and empty draft is discarded on exit`() = runTest(dispatcher) {
        assertNotNull(vm.prepareCameraCapture())
        assertNull(vm.prepareCameraCapture())
        val token = state.get<String>("cameraCapture")!!; writePhoto(token)
        vm.finishCameraCapture(false).join()
        assertFalse(CameraCaptureStore(app).file(token).exists()); assertFalse(vm.isPhotoBusy)
        assertTrue(AppDatabase.get(app).attachmentDao().getByNoteId(vm.currentNoteId).isEmpty())
        assertTrue(vm.flushOnExit()); assertTrue(AppDatabase.get(app).noteDao().getById(vm.currentNoteId)!!.isDeleted)
    }

    @Test fun `result cannot resurrect a note deleted while camera was open`() = runTest(dispatcher) {
        assertNotNull(vm.prepareCameraCapture())
        val token = state.get<String>("cameraCapture")!!; writePhoto(token)
        val db = AppDatabase.get(app)
        db.noteDao().softDelete(vm.currentNoteId)
        vm.finishCameraCapture(true).join()
        assertTrue(db.noteDao().getById(vm.currentNoteId)!!.isDeleted)
        assertTrue(db.attachmentDao().getByNoteId(vm.currentNoteId).isEmpty())
        assertFalse(CameraCaptureStore(app).file(token).exists())
        assertFalse(vm.isPhotoBusy)
    }

    @Test fun `database failure leaves no attachment or temporary file and allows another capture`() = runTest(dispatcher) {
        assertNotNull(vm.prepareCameraCapture())
        val token = state.get<String>("cameraCapture")!!; writePhoto(token)
        val db = AppDatabase.get(app)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_camera BEFORE INSERT ON attachments BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val previous = File(app.filesDir, "attachments").listFiles()?.map { it.name }?.toSet().orEmpty()
        vm.finishCameraCapture(true).join()
        assertEquals(EditorMessage.PHOTO_IMPORT_FAILED, vm.message)
        assertFalse(CameraCaptureStore(app).file(token).exists())
        assertEquals(previous, File(app.filesDir, "attachments").listFiles()?.map { it.name }?.toSet().orEmpty())
        assertTrue(db.attachmentDao().getByNoteId(vm.currentNoteId).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_camera")
        assertNotNull(vm.prepareCameraCapture()); vm.finishCameraCapture(false).join()
    }
}

/** VM tests exercise persistence/lifecycle; real JPEG/EXIF decoding is covered in NativeBitmap CameraCaptureTest.
 * Legacy Robolectric omits outMimeType, so supply the platform bounds result for this fixed JPEG fixture.
 */
@Implements(BitmapFactory::class)
class CameraBoundsShadow {
    companion object {
        @JvmStatic @Implementation
        fun decodeFile(path: String, options: BitmapFactory.Options): Bitmap? {
            check(options.inJustDecodeBounds) { "The camera import must never decode the full original" }
            check(File(path).inputStream().use { it.read() == 0xff && it.read() == 0xd8 })
            options.outWidth = 128; options.outHeight = 96; options.outMimeType = "image/jpeg"
            return null
        }
    }
}
