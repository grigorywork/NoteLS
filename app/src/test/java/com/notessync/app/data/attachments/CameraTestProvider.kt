package com.notessync.app.data.attachments

import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Bundle
import androidx.core.content.FileProvider
import com.notessync.app.R
import org.robolectric.Shadows.shadowOf

/** Each Robolectric application has a fresh filesDir; clear FileProvider's static root cache. */
internal fun registerCameraTestProvider(app: Application) {
    val info = ProviderInfo().apply {
        authority = "${app.packageName}.fileprovider"
        name = FileProvider::class.java.name
        packageName = app.packageName
        applicationInfo = app.applicationInfo
        exported = false
        grantUriPermissions = true
        metaData = Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS", R.xml.file_paths) }
    }
    shadowOf(app.packageManager).addOrUpdateProvider(info)
    FileProvider().attachInfo(app, info)
}
