package com.notessync.app.ui.notes.photos

import com.notessync.app.i18n.uiText

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

/** Full-resolution output with a temporary grant, including cameras that consume ClipData. */
class CameraCaptureContract : ActivityResultContracts.TakePicture() {
    override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        clipData = ClipData.newRawUri(uiText("Фото"), input)
    }
}
