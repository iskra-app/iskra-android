package wtf.iskra.design

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * «Убрать анимацию» в системе (Специальные возможности или масштаб анимации 0 в «Для разработчиков»).
 *
 * Как `respectsReduceMotion()` на iOS: читается один раз в корне ([IskraTheme]), а не в сотне мест,
 * где приложение движется, — по одному спрашивая, какое-то забудут, и забытое будет тем, от которого
 * человеку станет плохо. С включённым: изменения происходят сразу — без сдвигов, пружин и циклов.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
@ReadOnlyComposable
internal fun reduceMotionHere(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
