package com.notessync.app.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Этап 27: как выглядит выполненный пункт чек-листа.
enum class CompletedStyle { STRIKE, HIDE }

// Этап 29: акцентный цвет интерфейса (обводка, кнопки, выделение).
enum class AccentColor(val label: String) {
    DYNAMIC("Системный"),
    VIOLET("Фиолетовый"),
    BLUE("Синий"),
    GREEN("Зелёный"),
    ORANGE("Оранжевый"),
    PINK("Розовый"),
    TEAL("Бирюзовый")
}

// Этап 32: голосовой ввод — движок распознавания и структуризация речи.
enum class VoiceEngine(val label: String) {
    ONLINE("Онлайн (точнее, нужен интернет)"),
    LOCAL("На устройстве (без интернета)")
}

enum class VoiceStructure(val label: String) {
    RAW("Как есть"),
    POINTS("По пунктам"),
    MAIN_IDEA("Основная мысль")
}

// Этап 34: системные семейства шрифтов на выбор (стандартные, любимые людьми).
// Этап 38: + встроенные популярные шрифты (OFL, все с кириллицей) — лежат в res/font.
enum class AppFont(val label: String) {
    SYSTEM("Стандартный"),
    ROBOTO("Roboto"),
    OPEN_SANS("Open Sans"),
    MONTSERRAT("Montserrat"),
    INTER("Inter"),
    PT_SANS("PT Sans"),
    NUNITO("Nunito"),
    RUBIK("Rubik"),
    MANROPE("Manrope"),
    PLAYFAIR("Playfair Display"),
    MERRIWEATHER("Merriweather"),
    SANS("Гротеск"),
    SERIF("С засечками"),
    MONO("Моноширинный"),
    CURSIVE("Рукописный");

    /** Этап 38: встроенный файл шрифта (res/font) или системное семейство. */
    val isBundled: Boolean
        get() = this != SYSTEM && this != SANS && this != SERIF &&
            this != MONO && this != CURSIVE
}

// Этап 37: стиль интерфейса. GLASS — «матовое стекло» как в Kimi на iPhone:
// полупрозрачные панели с тонкой кромкой поверх мягкого градиента-авроры.
enum class InterfaceStyle(val label: String) {
    CLASSIC("Классика"),
    GLASS("Матовое стекло")
}

// Этап 36: как выглядит выполненная задача в виджете (зелёная / зачёркнутая / оба).
enum class WidgetCompletedStyle(val label: String) {
    GREEN("Зелёные"),
    STRIKE("Зачёркнутые"),
    BOTH("Зелёные и зачёркнутые");

    val usesGreen: Boolean get() = this == GREEN || this == BOTH
    val usesStrike: Boolean get() = this == STRIKE || this == BOTH
}

data class SettingsState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: Float = 1.0f,
    // Прозрачность виджета-календаря (Этап 8): 0.0 — невидимый фон, 1.0 — непрозрачный.
    val widgetAlpha: Float = 0.85f,
    val completedStyle: CompletedStyle = CompletedStyle.STRIKE,
    val accentColor: AccentColor = AccentColor.DYNAMIC,
    val voiceEngine: VoiceEngine = VoiceEngine.ONLINE,
    val voiceStructure: VoiceStructure = VoiceStructure.POINTS,
    // Этап 33: цветная подсветка задач в виджете (чтобы не сливались).
    val widgetColoredTasks: Boolean = false,
    // Этап 34: шрифт интерфейса.
    val appFont: AppFont = AppFont.SYSTEM,
    // Этап 37: классика или матовое стекло.
    val interfaceStyle: InterfaceStyle = InterfaceStyle.CLASSIC,
    // Этап 36: стиль выполненных задач в виджете.
    val widgetCompletedStyle: WidgetCompletedStyle = WidgetCompletedStyle.BOTH
)

/** Настройки приложения (Этап 6): тема и размер шрифта. */
class SettingsRepository private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _state.value = read()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    // Аудит 2026-11-11: неизвестное значение (даунгрейд, повреждённые prefs)
    // больше не роняет приложение — берётся значение по умолчанию.
    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching {
            enumValueOf<T>(prefs.getString(key, default.name)!!)
        }.getOrDefault(default)

    private fun read() = SettingsState(
        themeMode = enumPref(KEY_THEME, ThemeMode.SYSTEM),
        fontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f),
        widgetAlpha = prefs.getFloat(KEY_WIDGET_ALPHA, 0.85f),
        completedStyle = enumPref(KEY_COMPLETED_STYLE, CompletedStyle.STRIKE),
        accentColor = enumPref(KEY_ACCENT_COLOR, AccentColor.DYNAMIC),
        voiceEngine = enumPref(KEY_VOICE_ENGINE, VoiceEngine.ONLINE),
        voiceStructure = enumPref(KEY_VOICE_STRUCTURE, VoiceStructure.POINTS),
        widgetColoredTasks = prefs.getBoolean(KEY_WIDGET_COLORED_TASKS, false),
        appFont = enumPref(KEY_APP_FONT, AppFont.SYSTEM),
        interfaceStyle = enumPref(KEY_INTERFACE_STYLE, InterfaceStyle.CLASSIC),
        widgetCompletedStyle = enumPref(KEY_WIDGET_COMPLETED_STYLE, WidgetCompletedStyle.BOTH)
    )

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun setFontScale(scale: Float) {
        prefs.edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    fun setWidgetAlpha(alpha: Float) {
        prefs.edit().putFloat(KEY_WIDGET_ALPHA, alpha.coerceIn(0.15f, 1.0f)).apply()
    }

    fun setCompletedStyle(style: CompletedStyle) {
        prefs.edit().putString(KEY_COMPLETED_STYLE, style.name).apply()
    }

    fun setAccentColor(accent: AccentColor) {
        prefs.edit().putString(KEY_ACCENT_COLOR, accent.name).apply()
    }

    fun setVoiceEngine(engine: VoiceEngine) {
        prefs.edit().putString(KEY_VOICE_ENGINE, engine.name).apply()
    }

    fun setVoiceStructure(structure: VoiceStructure) {
        prefs.edit().putString(KEY_VOICE_STRUCTURE, structure.name).apply()
    }

    fun setWidgetColoredTasks(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIDGET_COLORED_TASKS, enabled).apply()
    }

    fun setAppFont(font: AppFont) {
        prefs.edit().putString(KEY_APP_FONT, font.name).apply()
    }

    fun setInterfaceStyle(style: InterfaceStyle) {
        prefs.edit().putString(KEY_INTERFACE_STYLE, style.name).apply()
    }

    fun setWidgetCompletedStyle(style: WidgetCompletedStyle) {
        prefs.edit().putString(KEY_WIDGET_COMPLETED_STYLE, style.name).apply()
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_WIDGET_ALPHA = "widget_alpha"
        private const val KEY_COMPLETED_STYLE = "completed_style"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_VOICE_ENGINE = "voice_engine"
        private const val KEY_VOICE_STRUCTURE = "voice_structure"
        private const val KEY_WIDGET_COLORED_TASKS = "widget_colored_tasks"
        private const val KEY_APP_FONT = "app_font"
        private const val KEY_INTERFACE_STYLE = "interface_style"
        private const val KEY_WIDGET_COMPLETED_STYLE = "widget_completed_style"

        // Пресеты прозрачности для кнопки в виджете (цикл по тапу).
        val WIDGET_ALPHA_PRESETS = floatArrayOf(1.0f, 0.85f, 0.6f, 0.35f)

        const val FONT_COMPACT = 0.85f
        const val FONT_NORMAL = 1.0f
        const val FONT_LARGE = 1.15f

        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
