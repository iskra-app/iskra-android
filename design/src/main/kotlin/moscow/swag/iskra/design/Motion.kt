package moscow.swag.iskra.design

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI

/**
 * Движение, записанное так же, как в iOS, и переведённое в Compose в одном месте.
 *
 * Числа в `Theme.Timing` переписаны из `Theme.swift` как есть — пружины словами SwiftUI
 * (`response`/`bounce`), кривые точками Безье. Переводит их этот файл, а не каждый экран сам:
 * перевод пружины — это формула, и формула, повторённая в девяти местах, в девятом окажется другой.
 *
 * Пружина SwiftUI задаётся откликом (`response`, период незатухающих колебаний) и долей затухания;
 * в Compose — жёсткостью при массе 1 и той же долей затухания. Жёсткость — это квадрат частоты:
 * `k = (2π / response)²`. `bounce` SwiftUI — это `1 − dampingFraction` для неотрицательного bounce.
 */
sealed interface Motion {
    /** Пружина SwiftUI `.spring(response:dampingFraction:)`. */
    data class Spring(val response: Float, val dampingFraction: Float) : Motion {
        val stiffness: Float get() = (2 * PI / response).let { (it * it).toFloat() }
    }

    /** Кривая: `.easeOut(duration:)`, `.timingCurve(…)`, `.linear(…)`. */
    data class Curve(val durationMillis: Int, val easing: Easing) : Motion

    fun <T> spec(): AnimationSpec<T> = when (this) {
        is Spring -> spring(dampingRatio = dampingFraction, stiffness = stiffness)
        is Curve -> tween(durationMillis = durationMillis, easing = easing)
    }

    companion object {
        /** `.spring(duration:bounce:)` SwiftUI: `duration` там — это отклик, а не время до покоя. */
        fun spring(duration: Float, bounce: Float): Spring = Spring(duration, 1f - bounce)

        fun easeOut(duration: Float) = Curve(millis(duration), CubicBezierEasing(0f, 0f, 0.58f, 1f))
        fun easeIn(duration: Float) = Curve(millis(duration), CubicBezierEasing(0.42f, 0f, 1f, 1f))
        fun easeInOut(duration: Float) = Curve(millis(duration), CubicBezierEasing(0.42f, 0f, 0.58f, 1f))
        fun linear(duration: Float) = Curve(millis(duration), LinearEasing)
        fun timingCurve(a: Float, b: Float, c: Float, d: Float, duration: Float) =
            Curve(millis(duration), CubicBezierEasing(a, b, c, d))

        private fun millis(seconds: Float) = (seconds * 1000).toInt()
    }
}
