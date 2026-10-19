package wtf.iskra.design

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeTonalSpot

/**
 * Цвет, которым раскрашено приложение: семь цветов радуги, моно без цвета и — только на Android —
 * «Как в системе» (Material You). Порт `Sources/IskraDesign/AccentTheme.swift`.
 *
 * Хранится по имени, как на iOS (`appearance.accent`), чтобы значения совпадали. `system` на iOS
 * не существует и за пределы устройства не уходит: оформление — свойство устройства, а не аккаунта.
 *
 * На Android акцент — не один цвет, а **вся** схема Material 3 (docs/PROMPT.md, 6.5): «Как в
 * системе» берёт динамическую схему целиком, остальные строят тональную из своего цвета тем же
 * алгоритмом (TonalSpot), моно — монохромную. Фон, поверхности и пузыри — из схемы.
 */
enum class AccentTheme(val storageName: String) {
    /** Material You: динамическая схема системы целиком. */
    SYSTEM("system"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    CYAN("cyan"),
    BLUE("blue"),
    VIOLET("violet"),

    /** Никакого цвета: серые тона схемы Monochrome. */
    MONO("mono"),
    ;

    companion object {
        /** Синий: таким приложение было всегда, и свежая установка не должна выглядеть настроенной. */
        val Default = BLUE

        const val STORAGE_KEY = "appearance.accent"

        /** «Окрасить интерфейс» — второй вопрос, а не девятый цвет. Выключено по умолчанию. */
        const val TINTS_INTERFACE_KEY = "appearance.tintsInterface"

        /** `system` бывает только с Android 12 (API 31): ниже динамических цветов нет. */
        fun isSystemAvailable(sdk: Int = Build.VERSION.SDK_INT): Boolean = sdk >= Build.VERSION_CODES.S

        /** Образцы в том порядке, в каком их показывает «Оформление»: «Как в системе» первым, если есть. */
        fun choices(sdk: Int = Build.VERSION.SDK_INT): List<AccentTheme> =
            entries.filter { it != SYSTEM || isSystemAvailable(sdk) }

        /**
         * Сохранённое значение → акцент. Незнакомое или пустое — по умолчанию; сохранённый `system`
         * на устройстве без динамических цветов (после переноса данных) читается как синий.
         */
        fun fromStorage(name: String?, sdk: Int = Build.VERSION.SDK_INT): AccentTheme {
            val theme = entries.firstOrNull { it.storageName == name } ?: return Default
            return if (theme == SYSTEM && !isSystemAvailable(sdk)) BLUE else theme
        }
    }
}

/**
 * Акцент, разрешённый для конкретного оформления: схема Material 3 и то, что экраны спрашивают у
 * акцента словами Искры. Каждое поле — роль схемы, а не свой цвет: на моно у схемы уже есть пара
 * «залито и то, что на нём читается», и белая плита с белыми словами не получается.
 */
@Immutable
data class AccentColors(
    val theme: AccentTheme,
    val isDark: Boolean,
    val scheme: ColorScheme,
) {
    /** Сам акцент (`primary`). */
    val color: Color get() = scheme.primary

    /** Что читается поверх акцента: стрелка в кнопке отправки, число непрочитанного. */
    val foreground: Color get() = scheme.onPrimary

    /** Чем окрашен включённый элемент: дорожка переключателя, иконки ссылок, «печатает…». */
    val control: Color get() = scheme.primary

    /** Свой пузырь — тональный контейнер, как в Google Messages; на обоях он и так непрозрачный. */
    val bubble: Color get() = scheme.primaryContainer
    val bubbleOnWallpaper: Color get() = scheme.primaryContainer

    /** Слова в своём пузыре. */
    val onBubble: Color get() = scheme.onPrimaryContainer

    /** Чужой пузырь: поверхность на тон выше страницы. */
    val incomingBubble: Color get() = scheme.surfaceContainerHigh
    val onIncomingBubble: Color get() = scheme.onSurface
}

/** Цвет, из которого строится тональная схема акцента: тот же, что у iOS, — «тот же красный». */
internal fun AccentTheme.seed(): Color = when (this) {
    AccentTheme.RED -> Color(0xFFFF3B30)
    AccentTheme.ORANGE -> Color(0xFFFF9500)
    AccentTheme.YELLOW -> Color(0xFFFFCC00)
    AccentTheme.GREEN -> Color(0xFF34C759)
    AccentTheme.CYAN -> Color(0xFF32ADE6)
    AccentTheme.BLUE, AccentTheme.SYSTEM -> Color(0xFF007AFF)
    AccentTheme.VIOLET -> Color(0xFFAF52DE)
    AccentTheme.MONO -> Color(0xFF808080)
}

/**
 * Схема Material 3 для акцента. Для [AccentTheme.SYSTEM] нужна динамическая схема ([system]); без
 * неё (ниже API 31) — синяя.
 */
fun AccentTheme.resolve(dark: Boolean, system: ColorScheme? = null): AccentColors {
    if (this == AccentTheme.SYSTEM && system != null) return AccentColors(this, dark, system)
    // Схема из цвета — чистая функция, а «Оформление» спрашивает все девять на каждой композиции.
    return built.getOrPut(this to dark) { build(dark) }
}

private val built = java.util.concurrent.ConcurrentHashMap<Pair<AccentTheme, Boolean>, AccentColors>()

private fun AccentTheme.build(dark: Boolean): AccentColors {
    val source = Hct.fromInt(seed().toArgb())
    val scheme = if (this == AccentTheme.MONO) {
        SchemeMonochrome(source, dark, CONTRAST)
    } else {
        SchemeTonalSpot(source, dark, CONTRAST)
    }
    return AccentColors(this, dark, scheme.toColorScheme())
}

/**
 * Разрешает акцент в композиции. Динамическая схема читается из [LocalContext] на каждой
 * композиции, а не кэшируется при старте: системные обои и тема меняются на ходу.
 */
@Composable
@ReadOnlyComposable
fun AccentTheme.resolveHere(dark: Boolean): AccentColors {
    if (this != AccentTheme.SYSTEM) return resolve(dark)
    // Проверка версии прямо здесь, а не через isSystemAvailable(): так её видит lint (NewApi).
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return AccentTheme.BLUE.resolve(dark)
    val context = LocalContext.current
    return resolve(dark, if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
}

/** Акцент, выбранный человеком. Схема та же, что у `MaterialTheme.colorScheme`. */
val LocalAccent = staticCompositionLocalOf { AccentTheme.Default.resolve(dark = false) }

/** Обычный контраст: тот, что у динамических цветов системы без настройки контраста. */
private const val CONTRAST = 0.0

private fun DynamicScheme.toColorScheme(): ColorScheme {
    fun c(argb: Int) = Color(argb)
    return lightColorScheme(
        primary = c(primary),
        onPrimary = c(onPrimary),
        primaryContainer = c(primaryContainer),
        onPrimaryContainer = c(onPrimaryContainer),
        inversePrimary = c(inversePrimary),
        secondary = c(secondary),
        onSecondary = c(onSecondary),
        secondaryContainer = c(secondaryContainer),
        onSecondaryContainer = c(onSecondaryContainer),
        tertiary = c(tertiary),
        onTertiary = c(onTertiary),
        tertiaryContainer = c(tertiaryContainer),
        onTertiaryContainer = c(onTertiaryContainer),
        background = c(background),
        onBackground = c(onBackground),
        surface = c(surface),
        onSurface = c(onSurface),
        surfaceVariant = c(surfaceVariant),
        onSurfaceVariant = c(onSurfaceVariant),
        surfaceTint = c(surfaceTint),
        inverseSurface = c(inverseSurface),
        inverseOnSurface = c(inverseOnSurface),
        error = c(error),
        onError = c(onError),
        errorContainer = c(errorContainer),
        onErrorContainer = c(onErrorContainer),
        outline = c(outline),
        outlineVariant = c(outlineVariant),
        scrim = c(scrim),
        surfaceBright = c(surfaceBright),
        surfaceDim = c(surfaceDim),
        surfaceContainer = c(surfaceContainer),
        surfaceContainerHigh = c(surfaceContainerHigh),
        surfaceContainerHighest = c(surfaceContainerHighest),
        surfaceContainerLow = c(surfaceContainerLow),
        surfaceContainerLowest = c(surfaceContainerLowest),
    )
}
