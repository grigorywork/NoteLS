package com.notessync.app.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import com.notessync.app.MainActivity
import com.notessync.app.R
import com.notessync.app.i18n.uiText

object PlannerShortcutLinks {
    private const val ACTION = "com.notessync.app.OPEN_PLANNER"
    private val uri = Uri.parse("notels://planner")
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION).setData(uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    fun matches(intent: Intent): Boolean = intent.action == ACTION && intent.data == uri
}

interface PlannerShortcutController {
    fun isSupported(): Boolean
    /** True means the launcher accepted the request, not that the user pinned it. */
    fun request(): Boolean
}

class AndroidPlannerShortcut(context: Context) : PlannerShortcutController {
    private val context = context.applicationContext
    private val manager get() = context.getSystemService(ShortcutManager::class.java)
    override fun isSupported(): Boolean = runCatching { manager?.isRequestPinShortcutSupported == true }.getOrDefault(false)
    override fun request(): Boolean = runCatching {
        val service = manager ?: return false
        if (!service.isRequestPinShortcutSupported) return false
        val shortcut = ShortcutInfo.Builder(context, ID)
            .setShortLabel(uiText("Планировщик"))
            .setLongLabel(uiText("Открыть календарь"))
            // The original user-supplied calendar artwork, independent of app icon choice.
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(PlannerShortcutLinks.intent(context))
            .build()
        service.requestPinShortcut(shortcut, null)
    }.getOrDefault(false)
    companion object { const val ID = "notels-planner" }
}
