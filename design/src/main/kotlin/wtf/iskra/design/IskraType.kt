package wtf.iskra.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import kotlin.math.exp

/**
 * Шрифт приложения — Inter (SIL OFL 1.1, `Licenses/inter.txt`). Весь текст: типографика Material ниже
 * целиком переведена на него, и поля ввода, меню и диалоги берут его оттуда же.
 *
 * Вариативный файл, а не девять: один `inter.ttf` (и курсив) с осью веса, и каждый вес — та же
 * гарнитура, нарисованная настоящей, а не синтезированной жирностью. Лежит в APK, а не скачивается:
 * загружаемые шрифты идут через сервисы Google, которых во `foss` нет.
 */
@OptIn(ExperimentalTextApi::class)
val Inter: FontFamily = FontFamily(
    WEIGHTS.flatMap { weight ->
        listOf(
            Font(R.font.inter, weight = weight, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))),
            Font(R.font.inter_italic, weight = weight, style = FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))),
        )
    },
)

private val WEIGHTS: List<FontWeight>
    get() = listOf(
        FontWeight.Thin, FontWeight.ExtraLight, FontWeight.Light, FontWeight.Normal, FontWeight.Medium,
        FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black,
    )

/**
 * Типографика Material 3, где каждый стиль — Inter; размеры — Material, межбуквенное — своё.
 *
 * Разрядка Material (+0.1…+0.5 sp) рассчитана на Roboto, и Inter с ней рассыпается — текст выглядел
 * реже, чем на iPhone. Вместо неё — динамические метрики самого Inter (rsms.me/inter/dynmetrics):
 * трекинг в em = a + b·e^(c·кегль). Мелкое почти без разрядки, крупное — плотнее: 16 sp — −0.011 em,
 * 22 sp — −0.018 em, заголовки — к −0.022 em. Как SF набирает текст по размеру на iOS.
 */
internal val IskraTypography: Typography = Typography().let { base ->
    fun TextStyle.inter() = copy(
        fontFamily = Inter,
        letterSpacing = interTracking(fontSize.value).em,
        fontFeatureSettings = INTER_FEATURES,
    )
    Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(),
        titleMedium = base.titleMedium.inter(),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(),
        bodyMedium = base.bodyMedium.inter(),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter(),
    )
}

/** Трекинг Inter для кегля в sp, в em. Коэффициенты — из «Dynamic Metrics» Inter. */
internal fun interTracking(size: Float): Float = TRACKING_A + TRACKING_B * exp(TRACKING_C * size)

/** Фичи Inter: зачёркнутый ноль, открытые формы цифр и букв, плоская «l» — `zero`, `ss01`, `ss02`, `ss07`. */
private const val INTER_FEATURES = "zero, ss01, ss02, ss07"

private const val TRACKING_A = -0.0223f
private const val TRACKING_B = 0.185f
private const val TRACKING_C = -0.1745f
