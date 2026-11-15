package com.notessync.app.settings

import com.notessync.app.i18n.uiText

enum class GlassTint(private val title: String) {
    THEME("По теме"), BLUE("Голубой"), TEAL("Бирюзовый"), LILAC("Сиреневый"),
    ROSE("Розовый"), AMBER("Янтарный"), GREEN("Зелёный"), GRAPHITE("Графитовый");
    val label: String get() = uiText(title)
}

enum class GlassBackdrop(private val title: String) {
    AURORA("Аврора"), BANDS("Полосы"), SOLID("Однотонный");
    val label: String get() = uiText(title)
}

/** Local appearance only; no database or background work. Midpoint retains legacy opacity. */
data class GlassAppearance(
    val transparency: Float = 0.5f,
    val frost: Float = 0f,
    val tint: GlassTint = GlassTint.THEME,
    val backdrop: GlassBackdrop = GlassBackdrop.AURORA
) {
    fun safe() = copy(
        transparency = if (transparency.isFinite()) transparency.coerceIn(0f, 1f) else 0.5f,
        frost = if (frost.isFinite()) frost.coerceIn(0f, 1f) else 0f
    )
}
