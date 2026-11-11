package com.notessync.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.notessync.app.R
import com.notessync.app.settings.AppFont

// Стандартная шкала шрифтов Material 3.
// Правило проекта: не использовать размеры меньше bodySmall (12sp) — никаких мелких строк.
val Typography = Typography()

// Этап 38: популярные шрифты (OFL), встроенные в приложение — Regular + Bold,
// все с кириллицей. Системные семейства (Этап 34) остаются как fallback.
private val RobotoFamily = FontFamily(
    Font(R.font.roboto_regular, FontWeight.Normal),
    Font(R.font.roboto_bold, FontWeight.Bold)
)
private val OpenSansFamily = FontFamily(
    Font(R.font.open_sans_regular, FontWeight.Normal),
    Font(R.font.open_sans_bold, FontWeight.Bold)
)
private val MontserratFamily = FontFamily(
    Font(R.font.montserrat_regular, FontWeight.Normal),
    Font(R.font.montserrat_bold, FontWeight.Bold)
)
private val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_bold, FontWeight.Bold)
)
private val PtSansFamily = FontFamily(
    Font(R.font.pt_sans_regular, FontWeight.Normal),
    Font(R.font.pt_sans_bold, FontWeight.Bold)
)
private val NunitoFamily = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_bold, FontWeight.Bold)
)
private val RubikFamily = FontFamily(
    Font(R.font.rubik_regular, FontWeight.Normal),
    Font(R.font.rubik_bold, FontWeight.Bold)
)
private val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_bold, FontWeight.Bold)
)
private val PlayfairFamily = FontFamily(
    Font(R.font.playfair_regular, FontWeight.Normal),
    Font(R.font.playfair_bold, FontWeight.Bold)
)
private val MerriweatherFamily = FontFamily(
    Font(R.font.merriweather_regular, FontWeight.Normal),
    Font(R.font.merriweather_bold, FontWeight.Bold)
)

/** Этап 34: системные семейства шрифтов. Этап 38: + встроенные популярные. */
fun appFontFamily(font: AppFont): FontFamily = when (font) {
    AppFont.SYSTEM -> FontFamily.Default
    AppFont.SANS -> FontFamily.SansSerif
    AppFont.SERIF -> FontFamily.Serif
    AppFont.MONO -> FontFamily.Monospace
    AppFont.CURSIVE -> FontFamily.Cursive
    AppFont.ROBOTO -> RobotoFamily
    AppFont.OPEN_SANS -> OpenSansFamily
    AppFont.MONTSERRAT -> MontserratFamily
    AppFont.INTER -> InterFamily
    AppFont.PT_SANS -> PtSansFamily
    AppFont.NUNITO -> NunitoFamily
    AppFont.RUBIK -> RubikFamily
    AppFont.MANROPE -> ManropeFamily
    AppFont.PLAYFAIR -> PlayfairFamily
    AppFont.MERRIWEATHER -> MerriweatherFamily
}

/** Масштаб (Этап 6: 0.85 / 1.0 / 1.15) и семейство шрифта (Этап 34) из настроек. */
fun scaledTypography(scale: Float, font: AppFont = AppFont.SYSTEM): Typography {
    if (scale == 1.0f && font == AppFont.SYSTEM) return Typography
    fun TextStyle.customized() = copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
        fontFamily = appFontFamily(font)
    )
    return Typography(
        displayLarge = Typography.displayLarge.customized(),
        displayMedium = Typography.displayMedium.customized(),
        displaySmall = Typography.displaySmall.customized(),
        headlineLarge = Typography.headlineLarge.customized(),
        headlineMedium = Typography.headlineMedium.customized(),
        headlineSmall = Typography.headlineSmall.customized(),
        titleLarge = Typography.titleLarge.customized(),
        titleMedium = Typography.titleMedium.customized(),
        titleSmall = Typography.titleSmall.customized(),
        bodyLarge = Typography.bodyLarge.customized(),
        bodyMedium = Typography.bodyMedium.customized(),
        bodySmall = Typography.bodySmall.customized(),
        labelLarge = Typography.labelLarge.customized(),
        labelMedium = Typography.labelMedium.customized(),
        labelSmall = Typography.labelSmall.customized()
    )
}
