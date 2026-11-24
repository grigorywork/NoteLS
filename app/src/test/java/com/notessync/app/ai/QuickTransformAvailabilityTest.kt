package com.notessync.app.ai

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, manifest = Config.NONE)
class QuickTransformAvailabilityTest {
    @Test fun `platform low RAM and missing network capability hide only the shortcut`() {
        val app = RuntimeEnvironment.getApplication()
        val manager = shadowOf(app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        shadowOf(app).grantPermissions(Manifest.permission.INTERNET)
        manager.setIsLowRamDevice(false)
        assertEquals(QuickTransformAvailability.AVAILABLE, quickTransformAvailability(app))
        manager.setIsLowRamDevice(true)
        assertEquals(QuickTransformAvailability.LOW_RAM, quickTransformAvailability(app))
        manager.setIsLowRamDevice(false)
        shadowOf(app).denyPermissions(Manifest.permission.INTERNET)
        assertEquals(QuickTransformAvailability.UNSUPPORTED, quickTransformAvailability(app))
    }
}
