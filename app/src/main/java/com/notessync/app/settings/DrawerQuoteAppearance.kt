package com.notessync.app.settings

data class DrawerQuoteAppearance(
    val enabled: Boolean = true, val customText: String = "", val font: AppFont = AppFont.SYSTEM,
    val size: Float = 16f, val textColor: Long = 0, val backgroundColor: Long = 0
) {
    fun safe() = copy(customText = customText.take(3000),
        size = if (size.isFinite()) size.coerceIn(12f, 32f) else 16f,
        textColor = textColor.takeIf { it == 0L || it in 0xFF000000L..0xFFFFFFFFL } ?: 0,
        backgroundColor = backgroundColor.takeIf { it == 0L || it in 0xFF000000L..0xFFFFFFFFL } ?: 0)
}
