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

/**
 * Шрифт приложения — Google Sans, SIL OFL 1.1 (`Licenses/google-sans.txt`). Один, без выбора (решение автора
 * 2026-10-24): весь текст — типографика Material ниже, и поля ввода, меню и диалоги берут его оттуда же.
 *
 * Вариативные файлы, а не по файлу на вес: один `.ttf` (и курсив) с осью веса, и каждый вес — та же
 * гарнитура, нарисованная настоящей, а не синтезированной жирностью. Лежат в APK, а не скачиваются:
 * загружаемые шрифты идут через сервисы Google, которых во `foss` нет.
 */

/**
 * Google Sans из google/fonts, урезанный до латиницы, кириллицы, греческого, вьетнамского и знаков
 * (`fontTools.subset`, оси и фичи целы): полный файл — 5 МБ на начертание из-за индийских и прочих
 * письменностей, которых нет среди языков приложения; их символы в сообщениях рисует системный шрифт.
 *
 * Ось веса у него 400–700: тоньше обычного нет, жирнее Bold — тоже. Более тонкие веса Compose сводит к
 * обычному, более жирные — к Bold, как CSS.
 */
@OptIn(ExperimentalTextApi::class)
val GoogleSans: FontFamily = FontFamily(
    WEIGHTS.filter { it.weight in FontWeight.Normal.weight..FontWeight.Bold.weight }.flatMap { weight ->
        listOf(
            Font(R.font.google_sans, weight = weight, style = FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))),
            Font(R.font.google_sans_italic, weight = weight, style = FontStyle.Italic, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))),
        )
    },
)

private val WEIGHTS: List<FontWeight>
    get() = listOf(
        FontWeight.Thin, FontWeight.ExtraLight, FontWeight.Light, FontWeight.Normal, FontWeight.Medium,
        FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black,
    )

/** Типографика Material 3: размеры — Material, гарнитура и межбуквенное — Google Sans. */
fun iskraTypography(): Typography = GoogleSansTypography

/**
 * Типографика Material 3 на Google Sans. Разрядка Material (+0.1…+0.5 sp) рассчитана на Roboto, а у
 * Google Sans свои просторные метрики, и с ней текст расползается; как в приложениях Google на этом
 * шрифте, межбуквенное — ноль.
 */
internal val GoogleSansTypography: Typography = Typography().map { style ->
    style.copy(fontFamily = GoogleSans, letterSpacing = 0.em)
}

private fun Typography.map(transform: (TextStyle) -> TextStyle) = Typography(
    displayLarge = transform(displayLarge),
    displayMedium = transform(displayMedium),
    displaySmall = transform(displaySmall),
    headlineLarge = transform(headlineLarge),
    headlineMedium = transform(headlineMedium),
    headlineSmall = transform(headlineSmall),
    titleLarge = transform(titleLarge),
    titleMedium = transform(titleMedium),
    titleSmall = transform(titleSmall),
    bodyLarge = transform(bodyLarge),
    bodyMedium = transform(bodyMedium),
    bodySmall = transform(bodySmall),
    labelLarge = transform(labelLarge),
    labelMedium = transform(labelMedium),
    labelSmall = transform(labelSmall),
)
