package com.notessync.app.ai

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager

/** Only the optional shortcut is gated; cloud models do not require a local GPU. */
enum class QuickTransformAvailability { AVAILABLE, LOW_RAM, UNSUPPORTED }

fun quickTransformAvailability(context: Context): QuickTransformAvailability = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return QuickTransformAvailability.UNSUPPORTED
    when {
        manager.isLowRamDevice -> QuickTransformAvailability.LOW_RAM
        context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED -> QuickTransformAvailability.UNSUPPORTED
        else -> QuickTransformAvailability.AVAILABLE
    }
}.getOrDefault(QuickTransformAvailability.UNSUPPORTED)
