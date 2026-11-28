package com.notessync.app.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.ui.settings.CalendarTypographySettings
import com.notessync.app.ui.theme.NoteSyncTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class, qualifiers="ru-w320dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarTypographyUiTest {
    @get:Rule val compose=createComposeRule()
    private val settings get() = SettingsRepository.get(RuntimeEnvironment.getApplication())
    private lateinit var view: View
    @Before fun before() {
        SettingsRepository::class.java.getDeclaredField("instance").apply { isAccessible=true; set(null,null) }
        settings.apply { setCalendarTypography(null,CalendarTextSize.NORMAL); setCalendarWidgetTypography(CalendarWidgetTypography()); setUiLanguage(UiLanguage.RUSSIAN) }
    }
    @After fun after() { settings.setUiLanguage(UiLanguage.RUSSIAN) }
    private fun content(english: Boolean) {
        settings.setUiLanguage(if (english) UiLanguage.ENGLISH else UiLanguage.RUSSIAN)
        compose.setContent {
            view=LocalView.current
            val state by settings.state.collectAsState()
            NoteSyncTheme(darkTheme=true, dynamicColor=false,fontScale=if(english) 1.6f else 1f) {
                Box(Modifier.requiredSize(320.dp,640.dp).testTag("typography-viewport")) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { CalendarTypographySettings(state,settings) }
                }
            }
        }
    }
    private fun inside(tag: String) {
        val node=compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNodeWithTag("typography-viewport").fetchSemanticsNode().boundsInRoot
        assertTrue(node.left>=viewport.left); assertTrue(node.right<=viewport.right+1f)
    }
    private fun screenshot(name: String) {
        val bounds=compose.onNodeWithTag("typography-viewport").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val bitmap=Bitmap.createBitmap(bounds.width.toInt(),bounds.height.toInt(),Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap);canvas.translate(-bounds.left,-bounds.top);view.draw(canvas)
            val file=File("build/reports/calendar-typography-preview/$name.png").apply{parentFile.mkdirs()}
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun `RU320 calendar controls select bundled font resize preview and reset independently`() {
        content(false)
        compose.onNodeWithTag("settings-choice-calendar-font").performClick()
        compose.onNodeWithText("PT Sans").performScrollTo().performClick()
        compose.onNodeWithTag("calendar-size-LARGE").performClick()
        compose.runOnIdle { assertEquals(AppFont.PT_SANS,settings.state.value.calendarFont);assertEquals(CalendarTextSize.LARGE,settings.state.value.calendarTextSize) }
        inside("calendar-text-preview"); screenshot("calendar-ru-320")
        compose.onNodeWithTag("calendar-text-reset").performScrollTo().performClick()
        compose.runOnIdle { assertNull(settings.state.value.calendarFont);assertEquals(CalendarTextSize.NORMAL,settings.state.value.calendarTextSize) }
    }
    @Test fun `English large font widget controls stay reachable and preview reset preserves calendar choices`() {
        settings.setCalendarTypography(AppFont.INTER,CalendarTextSize.COMPACT)
        content(true)
        compose.onNodeWithTag("settings-choice-calendar-widget-font").performScrollTo().performClick()
        compose.onNodeWithText("Monospace").performScrollTo().performClick()
        compose.onNodeWithTag("calendar-widget-size-LARGE").performScrollTo().performClick()
        inside("calendar-widget-text-preview"); inside("calendar-widget-text-reset"); screenshot("widgets-en-large-320")
        compose.runOnIdle { assertEquals(CalendarWidgetFont.MONO,settings.state.value.calendarWidgetTypography.font);assertEquals(CalendarTextSize.LARGE,settings.state.value.calendarWidgetTypography.size) }
        compose.onNodeWithTag("calendar-widget-text-reset").performClick()
        compose.runOnIdle { assertEquals(CalendarWidgetTypography(),settings.state.value.calendarWidgetTypography);assertEquals(AppFont.INTER,settings.state.value.calendarFont);assertEquals(CalendarTextSize.COMPACT,settings.state.value.calendarTextSize) }
    }
}
