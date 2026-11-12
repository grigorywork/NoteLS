package com.notessync.app.settings

import com.notessync.app.i18n.uiText

import android.content.Context
import com.notessync.app.i18n.UiLanguage
import com.notessync.app.i18n.UiStrings
import com.notessync.app.reminders.createReminderChannel
import com.notessync.app.widget.CalendarWidgetProvider
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class NotesLayout { LIST, GRID }

// Этап 27: как выглядит выполненный пункт чек-листа.
enum class CompletedStyle { STRIKE, HIDE }

// Этап 29: акцентный цвет интерфейса (обводка, кнопки, выделение).
enum class AccentColor(private val labelRu: String) {
    DYNAMIC("Системный"),
    VIOLET("Фиолетовый"),
    BLUE("Синий"),
    GREEN("Зелёный"),
    ORANGE("Оранжевый"),
    PINK("Розовый"),
    TEAL("Бирюзовый");
    val label: String get() = uiText(labelRu)
}

// Этап 32: голосовой ввод — движок распознавания и структуризация речи.
enum class VoiceEngine(private val labelRu: String) {
    ONLINE("Онлайн (точнее, нужен интернет)"),
    LOCAL("На устройстве (без интернета)");
    val label: String get() = uiText(labelRu)
}

enum class VoiceStructure(private val labelRu: String) {
    RAW("Как есть"),
    POINTS("По пунктам"),
    MAIN_IDEA("Основная мысль");
    val label: String get() = uiText(labelRu)
}

// Этап 34: системные семейства шрифтов на выбор (стандартные, любимые людьми).
// Этап 38: + встроенные популярные шрифты (OFL, все с кириллицей) — лежат в res/font.
enum class AppFont(private val labelRu: String) {
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
    val label: String get() = uiText(labelRu)
}

// Этап 37: стиль интерфейса. GLASS — «матовое стекло» как в Kimi на iPhone:
// полупрозрачные панели с тонкой кромкой поверх мягкого градиента-авроры.
enum class InterfaceStyle(private val labelRu: String) {
    CLASSIC("Классика"),
    GLASS("Матовое стекло");
    val label: String get() = uiText(labelRu)
}

// Этап 39: как часто делать автобэкап заметок.
// intervalMillis = -1 — после каждой заметки; 0 — только вручную.
enum class BackupFrequency(private val labelRu: String, val intervalMillis: Long) {
    MANUAL("Только вручную", 0L),
    AFTER_NOTE("После каждой заметки", -1L),
    DAILY("Раз в день", 86_400_000L),
    EVERY_3_DAYS("Раз в 3 дня", 3 * 86_400_000L),
    EVERY_10_DAYS("Раз в 10 дней", 10 * 86_400_000L),
    MONTHLY("Раз в месяц", 30 * 86_400_000L);
    val label: String get() = uiText(labelRu)
}

// Этап 36: как выглядит выполненная задача в виджете (зелёная / зачёркнутая / оба).
enum class WidgetCompletedStyle(private val labelRu: String) {
    GREEN("Зелёные"),
    STRIKE("Зачёркнутые"),
    BOTH("Зелёные и зачёркнутые");

    val usesGreen: Boolean get() = this == GREEN || this == BOTH
    val usesStrike: Boolean get() = this == STRIKE || this == BOTH
    val label: String get() = uiText(labelRu)
}

data class SettingsState(
    val uiLanguage: UiLanguage = UiLanguage.RUSSIAN,
    val notesLayout: NotesLayout = NotesLayout.LIST,
    val editorHistory: Boolean = true,
    val showTables: Boolean = true,
    val photoResize: Boolean = true,
    val cameraCapture: Boolean = true,
    val quickTaskTime: Boolean = true,
    val advancedFormatting: Boolean = true,
    val showTaskPriorities: Boolean = true,
    val drawerQuote: DrawerQuoteAppearance = DrawerQuoteAppearance(),
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
    val widgetCompletedStyle: WidgetCompletedStyle = WidgetCompletedStyle.BOTH,
    // Этап 39: частота автобэкапа и время последнего (0 — ещё не делался).
    val backupFrequency: BackupFrequency = BackupFrequency.EVERY_10_DAYS,
    val lastBackupAt: Long = 0L,
    val lastAutoBackupAt: Long = 0L
)

/** Настройки приложения (Этап 6): тема и размер шрифта. */
class SettingsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val current = read()
        _state.value = current
        UiStrings.language = current.uiLanguage
    }

    init {
        UiStrings.language = _state.value.uiLanguage
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    // Аудит 2026-11-11: неизвестное значение (даунгрейд, повреждённые prefs)
    // больше не роняет приложение — берётся значение по умолчанию.
    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching {
            enumValueOf<T>(prefs.getString(key, default.name)!!)
        }.getOrDefault(default)

    private fun read() = SettingsState(
        uiLanguage = enumPref("ui_language", UiLanguage.RUSSIAN),
        notesLayout = enumPref("notes_layout", NotesLayout.LIST),
        editorHistory = prefs.getBoolean("editor_history", true),
        showTables = prefs.getBoolean("show_tables", true),
        photoResize = prefs.getBoolean("photo_resize", true),
        cameraCapture = prefs.getBoolean("camera_capture", true),
        quickTaskTime = prefs.getBoolean("quick_task_time", true),
        advancedFormatting = prefs.getBoolean("advanced_formatting", true),
        showTaskPriorities = prefs.getBoolean("show_priorities", true),
        drawerQuote = runCatching { DrawerQuoteAppearance(
            prefs.getBoolean("drawer_quote_enabled", true), prefs.getString("drawer_quote_text", "").orEmpty(),
            enumPref("drawer_quote_font", AppFont.SYSTEM), prefs.getFloat("drawer_quote_size", 16f),
            prefs.getLong("drawer_quote_text_color", 0L), prefs.getLong("drawer_quote_background", 0L)
        ).safe() }.getOrDefault(DrawerQuoteAppearance()),
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
        widgetCompletedStyle = enumPref(KEY_WIDGET_COMPLETED_STYLE, WidgetCompletedStyle.BOTH),
        backupFrequency = enumPref(KEY_BACKUP_FREQUENCY, BackupFrequency.EVERY_10_DAYS),
        lastBackupAt = prefs.getLong(KEY_LAST_BACKUP_AT, 0L),
        lastAutoBackupAt = prefs.getLong(KEY_LAST_AUTO_BACKUP_AT, 0L)
    )

    fun setUiLanguage(language: UiLanguage) {
        if (_state.value.uiLanguage == language) return
        prefs.edit().putString("ui_language", language.name).apply()
        val current = read()
        _state.value = current
        UiStrings.language = current.uiLanguage
        appContext.createReminderChannel()
        CalendarWidgetProvider.refreshAll(appContext, force = true)
    }

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

    fun setNotesLayout(value: NotesLayout) { prefs.edit().putString("notes_layout", value.name).apply(); _state.value = read() }

    fun setQuickTaskTime(value: Boolean) { prefs.edit().putBoolean("quick_task_time", value).apply(); _state.value = read() }

    fun setCameraCapture(value: Boolean) { prefs.edit().putBoolean("camera_capture", value).apply(); _state.value = read() }

    fun setPhotoResize(value: Boolean) { prefs.edit().putBoolean("photo_resize", value).apply(); _state.value = read() }

    fun setShowTables(value: Boolean) { prefs.edit().putBoolean("show_tables", value).apply(); _state.value = read() }

    fun setEditorHistory(value: Boolean) { prefs.edit().putBoolean("editor_history", value).apply(); _state.value = read() }

    fun setAdvancedFormatting(value: Boolean) { prefs.edit().putBoolean("advanced_formatting", value).apply(); _state.value = read() }
    fun setShowTaskPriorities(value: Boolean) { prefs.edit().putBoolean("show_priorities", value).apply(); _state.value = read() }
    fun setDrawerQuoteAppearance(value: DrawerQuoteAppearance) {
        val v = value.safe()
        prefs.edit().putBoolean("drawer_quote_enabled", v.enabled).putString("drawer_quote_text", v.customText)
            .putString("drawer_quote_font", v.font.name).putFloat("drawer_quote_size", v.size)
            .putLong("drawer_quote_text_color", v.textColor).putLong("drawer_quote_background", v.backgroundColor).apply()
        _state.value = read()
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

    fun setBackupFrequency(frequency: BackupFrequency) {
        prefs.edit().putString(KEY_BACKUP_FREQUENCY, frequency.name).apply()
    }

    fun setLastBackupAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_BACKUP_AT, timestamp).apply()
        _state.value = read()
    }

    fun setLastAutoBackupAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_AUTO_BACKUP_AT, timestamp)
            .putLong(KEY_LAST_BACKUP_AT, timestamp).apply()
        _state.value = read()
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
        private const val KEY_BACKUP_FREQUENCY = "backup_frequency"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_AUTO_BACKUP_AT = "last_auto_backup_at"

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
