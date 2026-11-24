package com.notessync.app.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.notessync.app.R

/** Stable manifest names: keep these aliases across future updates. */
enum class LauncherIcon(val alias: String, val title: String, val artwork: Int) {
    GLASS("GlassIcon", "Розовое стекло", R.drawable.launcher_glass),
    FROSTED("FrostedIcon", "Матовое стекло", R.drawable.launcher_frosted),
    PAPER("PaperIcon", "Лист с галочкой", R.drawable.launcher_paper)
}

internal interface LauncherComponents {
    fun enabled(icon: LauncherIcon): Boolean
    fun set(icon: LauncherIcon, enabled: Boolean)
    /** null on older Android, where target-first switching keeps a launch entry throughout. */
    fun atomicSelect(icon: LauncherIcon): Boolean = false
}

internal class AndroidLauncherComponents(context: Context) : LauncherComponents {
    private val pm = context.packageManager
    private val packageName = context.packageName
    private fun component(icon: LauncherIcon) = ComponentName(packageName, "$packageName.launcher.${icon.alias}")
    override fun enabled(icon: LauncherIcon): Boolean = when (pm.getComponentEnabledSetting(component(icon))) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LauncherIcon.GLASS
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }
    override fun set(icon: LauncherIcon, enabled: Boolean) {
        pm.setComponentEnabledSetting(component(icon), if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
    override fun atomicSelect(icon: LauncherIcon): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        pm.setComponentEnabledSettings(LauncherIcon.entries.map {
            PackageManager.ComponentEnabledSetting(component(it), if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        })
        return true
    }
}

/** PackageManager is the durable source of truth; no backup/sync of this device-only choice. */
class LauncherIcons internal constructor(private val components: LauncherComponents) {
    constructor(context: Context) : this(AndroidLauncherComponents(context.applicationContext))

    @Synchronized fun current(): LauncherIcon {
        val enabled = LauncherIcon.entries.filter(components::enabled)
        if (enabled.size == 1) return enabled.single()
        // Repair interrupted legacy switches, preserving a launch entry before removing extras.
        return select(enabled.firstOrNull() ?: LauncherIcon.GLASS)
    }

    @Synchronized fun select(target: LauncherIcon): LauncherIcon {
        val before = LauncherIcon.entries.associateWith(components::enabled)
        if (before.count { it.value } == 1 && before[target] == true) return target
        try {
            if (!components.atomicSelect(target)) {
                components.set(target, true)
                LauncherIcon.entries.filter { it != target }.forEach { components.set(it, false) }
            }
            check(LauncherIcon.entries.filter(components::enabled) == listOf(target))
            return target
        } catch (error: Exception) {
            // Re-enable original entries first: a failed transition must not strand the app.
            val fallback = before.filterValues { it }.keys.ifEmpty { setOf(target) }
            fallback.forEach { runCatching { components.set(it, true) } }
            LauncherIcon.entries.filter { it !in fallback }.forEach { runCatching { components.set(it, false) } }
            throw error
        }
    }
}
