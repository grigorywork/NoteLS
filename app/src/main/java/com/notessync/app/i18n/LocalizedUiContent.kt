package com.notessync.app.i18n

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.AssetManager
import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Scoped resources for Material date/time pickers. The Activity, drafts and animation state survive. */
@Composable
fun LocalizedUiContent(content: @Composable () -> Unit) {
    val base = LocalContext.current
    val current = LocalConfiguration.current
    val language = UiStrings.language
    val configuration = remember(current, language) { Configuration(current).apply { setLocale(language.locale) } }
    val context = remember(base, configuration) { UiContext(base, configuration) }
    CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
        LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/** Keep the real Activity as baseContext so biometric/activity-result adapters can find it. */
private class UiContext(base: Context, configuration: Configuration) : ContextWrapper(base) {
    private val localized = base.createConfigurationContext(configuration)
    private val localizedTheme by lazy { localized.resources.newTheme().apply { setTo(base.theme) } }
    override fun getResources(): Resources = localized.resources
    override fun getAssets(): AssetManager = localized.assets
    override fun getTheme(): Resources.Theme = localizedTheme
    override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE)
        LayoutInflater.from(baseContext).cloneInContext(this) else super.getSystemService(name)
}
