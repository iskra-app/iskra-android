package moscow.swag.iskra.design

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

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

/** Типографика Material 3, где каждый стиль — Inter; размеры и интервалы — Material, пока не свои. */
internal val IskraTypography: Typography = Typography().let { base ->
    fun TextStyle.inter() = copy(fontFamily = Inter)
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
