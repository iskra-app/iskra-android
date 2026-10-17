package moscow.swag.iskra.design

import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Цвет, которым раскрашено приложение: семь цветов радуги, моно без цвета и — только на Android —
 * «Как в системе» (Material You). Порт `Sources/IskraDesign/AccentTheme.swift`.
 *
 * Хранится по имени, как на iOS (`appearance.accent`), чтобы значения совпадали. `system` на iOS
 * не существует и за пределы устройства не уходит: оформление — свойство устройства, а не аккаунта.
 */
enum class AccentTheme(val storageName: String) {
    /** Material You: `primary` динамической схемы как акцент, `onPrimary` как то, что на нём читается. */
    SYSTEM("system"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    CYAN("cyan"),
    BLUE("blue"),
    VIOLET("violet"),

    /** Никакого цвета: чёрное на белом, белое на чёрном. */
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
 * Акцент, разрешённый для конкретного оформления: всё, что экраны спрашивают у акцента.
 *
 * На iOS это вычисляемые свойства `AccentTheme` и `ShapeStyle` системы. Здесь цвета — конкретные
 * значения: системные цвета iOS (светлый и тёмный вариант), потому что «тот же красный» на обеих
 * платформах — это тот же красный, а не красный Material.
 */
@Immutable
data class AccentColors(
    val theme: AccentTheme,
    val isDark: Boolean,
    /** Сам акцент. На моно — чистый чёрный или белый (`solid(in:)` на iOS). */
    val color: Color,
    /** Что читается поверх акцента: слова в своём пузыре, стрелка в кнопке отправки. */
    val foreground: Color,
    /** Чем заливается включённый системный элемент: дорожка переключателя, ползунок. */
    val control: Color,
    /** Заливка своего пузыря без обоев и на обоях. */
    val bubble: Color,
    val bubbleOnWallpaper: Color,
    /** Слова в своём пузыре. */
    val onBubble: Color,
    /** Сколько акцента ложится на всё приложение при «Окрасить интерфейс». */
    val washOpacity: Float,
)

/** Неизменные цвета страницы, не акцента. Порт расширений `ShapeStyle` из `Theme.swift`. */
object PageColors {
    /** `Color.primary` iOS: чёрный в светлом, белый в тёмном. */
    fun primary(dark: Boolean) = if (dark) Color.White else Color.Black

    fun background(dark: Boolean) = if (dark) Color.Black else Color.White

    /** Чужой пузырь: в светлом 5 % чёрного, в тёмном 8 % белого — тот же шёпот, а не то же число. */
    fun incomingBubble(dark: Boolean) = if (dark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)

    /** Чужой пузырь на обоях: непрозрачный, иначе сквозь слова видно фото. */
    fun incomingBubbleOnWallpaper(dark: Boolean) = if (dark) Color(0xFF2B2B2B) else Color(0xFFFCFCFC)

    /** Свой пузырь моно на обоях: тоном тяжелее чужого. */
    fun ownBubbleOnWallpaper(dark: Boolean) = if (dark) Color(0xFF4D4D4D) else Color(0xFFDEDEDE)

    /** Своя реакция: та же серая пилюля, нажатая чуть сильнее. */
    fun ownReaction(dark: Boolean) = primary(dark).copy(alpha = 0.16f)
}

/** Системные цвета iOS (светлый, тёмный). Тот же красный на обеих платформах. */
private fun AccentTheme.paletteColor(dark: Boolean): Color = when (this) {
    AccentTheme.RED -> if (dark) Color(0xFFFF453A) else Color(0xFFFF3B30)
    AccentTheme.ORANGE -> if (dark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    AccentTheme.YELLOW -> if (dark) Color(0xFFFFD60A) else Color(0xFFFFCC00)
    AccentTheme.GREEN -> if (dark) Color(0xFF30D158) else Color(0xFF34C759)
    AccentTheme.CYAN -> if (dark) Color(0xFF64D2FF) else Color(0xFF32ADE6)
    AccentTheme.BLUE -> if (dark) Color(0xFF0A84FF) else Color(0xFF007AFF)
    AccentTheme.VIOLET -> if (dark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
    AccentTheme.MONO -> PageColors.primary(dark)
    AccentTheme.SYSTEM -> error("system разрешается через динамическую схему")
}

/**
 * Разрешает акцент для оформления [dark]. Для [AccentTheme.SYSTEM] нужны цвета динамической схемы
 * ([systemPrimary], [systemOnPrimary]); остальная палитра (фон, пузыри) остаётся Искриной.
 */
fun AccentTheme.resolve(dark: Boolean, systemPrimary: Color? = null, systemOnPrimary: Color? = null): AccentColors {
    val color: Color
    val foreground: Color
    when (this) {
        AccentTheme.SYSTEM -> {
            color = requireNotNull(systemPrimary) { "для system нужен primary динамической схемы" }
            foreground = requireNotNull(systemOnPrimary) { "для system нужен onPrimary динамической схемы" }
        }
        // На моно поверх акцента может стоять только цвет фона.
        AccentTheme.MONO -> {
            color = paletteColor(dark)
            foreground = PageColors.background(dark)
        }
        // Белое на жёлтом не читается совсем: единственное исключение, и не пограничное.
        AccentTheme.YELLOW -> {
            color = paletteColor(dark)
            foreground = Color.Black
        }
        else -> {
            color = paletteColor(dark)
            foreground = Color.White
        }
    }
    val mono = this == AccentTheme.MONO
    return AccentColors(
        theme = this,
        isDark = dark,
        color = color,
        foreground = foreground,
        // На моно белая дорожка под белой ручкой переключателя — переключатель без видимого
        // состояния. Поэтому серый, отведённый от чёрно-белой пары.
        control = if (mono) {
            if (dark) Color.White.copy(alpha = 0.38f) else Color.Black.copy(alpha = 0.72f)
        } else {
            color
        },
        // Пузырь, залитый чистым чёрным, превращает переписку в домино: у моно — серый.
        bubble = if (mono) PageColors.primary(dark).copy(alpha = 0.16f) else color,
        bubbleOnWallpaper = if (mono) PageColors.ownBubbleOnWallpaper(dark) else color,
        onBubble = if (mono) PageColors.primary(dark) else foreground,
        washOpacity = if (mono) Theme.Tint.MONO else Theme.Tint.INTERFACE,
    )
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
    val scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    return resolve(dark, scheme.primary, scheme.onPrimary)
}

/** Акцент, выбранный человеком. Читать его, а не `MaterialTheme.colorScheme.primary`. */
val LocalAccent = staticCompositionLocalOf { AccentTheme.Default.resolve(dark = false) }
