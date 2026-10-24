package wtf.iskra.design

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Корень оформления: Material 3 целиком — схема, типографика и формы (docs/PROMPT.md, 6.1, 6.5).
 *
 * Схема — из акцента ([AccentTheme.resolveHere]): динамическая системная на «Как в системе»,
 * тональная из своего цвета на остальных. Шрифт — Google Sans,
 * размеры — шкала Material.
 * Вёрстка и наполнение экранов — как на iOS; внешний вид — Google.
 */
@Composable
fun IskraTheme(
    accent: AccentTheme = AccentTheme.Default,
    appearance: AppearanceMode = AppearanceMode.Default,
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    val colors = accent.resolveHere(dark)
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
        MaterialTheme(colorScheme = colors.scheme, typography = iskraTypography(), content = content)
    }
}
