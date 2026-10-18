package wtf.iskra.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Корень оформления: Material 3 как каркас, внешний вид — Искрин (docs/PROMPT.md, 6.1).
 *
 * Material You меняет только акцент (`primary`/`onPrimary`), а фон, поверхности и пузыри остаются
 * своими. Экраны берут акцент из [LocalAccent], а не из `MaterialTheme.colorScheme.primary`: на моно
 * у Material нет пары «акцент и то, что на нём читается», и системная кнопка выходила белой плитой
 * с белыми словами (раздел 6.4).
 */
@Composable
fun IskraTheme(
    accent: AccentTheme = AccentTheme.Default,
    appearance: AppearanceMode = AppearanceMode.Default,
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    val colors = accent.resolveHere(dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.color,
        onPrimary = colors.foreground,
        background = PageColors.background(dark),
        onBackground = PageColors.primary(dark),
        surface = PageColors.background(dark),
        onSurface = PageColors.primary(dark),
    )
    // Значки строки состояния и навигации — под тему *приложения*: выбранная тёмная тема на светлой
    // системе иначе оставляла бы тёмные часы и батарею на тёмном фоне.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(
        LocalAccent provides colors,
        LocalReduceMotion provides reduceMotionHere(),
    ) {
        MaterialTheme(colorScheme = scheme, typography = IskraTypography, content = content)
    }
}
