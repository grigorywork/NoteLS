package com.notessync.app.sync

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.settings.SettingsRepository
import com.notessync.app.sync.android.*
import com.notessync.app.sync.core.*
import com.notessync.app.sync.qr.SyncQr
import com.notessync.app.ui.screens.SyncScreen
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SyncUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val connects = AtomicInteger()
    private var listener: ServerSocket? = null
    private lateinit var vm: SyncViewModel
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("fixture", ""))
        SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN)
        val connections = object : SyncConnections {
            override fun localAddress() = "192.168.1.2"
            override fun listen(address: String, port: Int) = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { listener = it }
            override fun socket() = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) { connects.incrementAndGet(); throw java.io.IOException("Fixture network disabled") }
            }
        }
        vm = SyncViewModel(app, FakeSyncDataSource(), connections); models.put("sync", vm)
    }
    @After fun after() { compose.runOnIdle { models.clear() }; listener?.close(); SettingsRepository.get(app).setUiLanguage(UiLanguage.RUSSIAN) }
    @Test fun `manual UI requires a LAN address and full key then starts only the explicit action`() {
        compose.setContent { MaterialTheme { SyncScreen({}, {}, vm) } }
        compose.onNodeWithText("Ввести код вручную").performClick()
        compose.onNodeWithText("IP-адрес").performTextInput("192.168.1.2")
        compose.onNodeWithText("Ключ сопряжения (64 символа)").performTextInput("ABCD2345")
        compose.onNodeWithText("Начать синхронизацию").assertIsNotEnabled(); assertEquals(0, connects.get())
        compose.onNodeWithText("Ключ сопряжения (64 символа)").performTextReplacement("ab".repeat(32))
        compose.onNodeWithText("Начать синхронизацию").assertIsEnabled().performClick()
        compose.waitUntil(8_000) { compose.waitForIdle(); connects.get() == 1 }
        compose.waitUntil(8_000) { compose.waitForIdle(); compose.onAllNodesWithText("Синхронизация не удалась").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun `QR payload is full strength with a privacy warning and cancelling clears invitation`() {
        SettingsRepository.get(app).setUiLanguage(UiLanguage.ENGLISH)
        compose.setContent { MaterialTheme { SyncScreen({}, {}, vm) } }
        compose.onNodeWithText("Show QR code").performClick()
        compose.waitUntil(8_000) { compose.waitForIdle(); compose.onAllNodesWithContentDescription("Pairing QR code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("The code is valid for 5 minutes", substring = true).performScrollTo().assertIsDisplayed()
        val state = vm.uiState.value
        val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals("", clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithText("Copy key").performScrollTo().performClick()
        assertEquals(state.sessionCode, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertTrue(clipboard.primaryClip!!.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        assertTrue(SyncQr.decode(state.qrPayload) is SyncQr.DecodeResult.Ok)
        val bitmap = com.notessync.app.sync.qr.QrGenerator.bitmap(state.qrPayload)
        val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val decoded = com.google.zxing.qrcode.QRCodeReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(
            com.google.zxing.RGBLuminanceSource(bitmap.width, bitmap.height, pixels))))
        assertEquals(state.qrPayload, decoded.text)
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("", vm.uiState.value.qrPayload); assertEquals("", vm.uiState.value.sessionCode) }
    }
}
