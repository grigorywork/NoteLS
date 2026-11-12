package com.notessync.app.notes.text

/** Bound work for a visible card without cutting a UTF16 surrogate pair. The document is untouched. */
fun notePreviewText(text: String, limit: Int = 2_000): String {
    require(limit > 0)
    if (text.length <= limit) return text
    val end = if (text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit - 1 else limit
    return text.substring(0, end)
}
