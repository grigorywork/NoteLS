package com.notessync.app.i18n

/** Fixed UI messages only. English data is allocated on first use; Russian is the original key. */
object UiCatalog {
    internal val english: Map<String, String> by lazy {
        buildMap(730) {
            addInfrastructureEnglish()
            addEditorEnglish()
            addNavigationEnglish()
            addSettingsEnglish()
            addFeaturesEnglish()
            addQuotesEnglish()
            addBackupSecurityEnglish()
            addFreeModelsEnglish()
            addDiaryEnglish()
        }
    }

    fun translate(language: UiLanguage, key: String, arguments: Array<out Any?> = emptyArray(), context: String? = null): String {
        val template = if (language == UiLanguage.ENGLISH) {
            (if (context == null) null else english["$context|$key"]) ?: english[key] ?: key
        } else key
        if (arguments.isEmpty()) return template
        // One pass: percent signs and placeholders inside a user argument remain literal.
        return buildString(template.length + 16) {
            var offset = 0
            while (offset < template.length) {
                if (template[offset] == '%') {
                    var end = offset + 1
                    while (end < template.length && template[end] in '0'..'9') end++
                    if (end > offset + 1 && end + 1 < template.length && template[end] == '$' && template[end + 1] == 's') {
                        val index = template.substring(offset + 1, end).toIntOrNull()?.minus(1)
                        if (index != null && index in arguments.indices) {
                            append(arguments[index].toString()); offset = end + 2; continue
                        }
                    }
                }
                append(template[offset]); offset++
            }
        }
    }
}
