package com.notessync.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class UiLanguage(val tag: String, val nativeName: String, val locale: Locale) {
    RUSSIAN("ru", "Русский", Locale("ru", "RU")),
    ENGLISH("en", "English", Locale.US)
}

/** Observable only by UI readers. Never change the device locale or transform user content. */
object UiStrings {
    var language by mutableStateOf(UiLanguage.RUSSIAN)
        internal set
    val locale: Locale get() = language.locale
}

fun uiText(key: String, vararg arguments: Any?, context: String? = null): String =
    UiCatalog.translate(UiStrings.language, key, arguments, context)
