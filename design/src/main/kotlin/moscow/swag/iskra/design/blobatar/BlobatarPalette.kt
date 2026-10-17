//  Ported from blobatar (https://blobatar.dev), MIT licence,
//  Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of IskraDesign/Blobatar/BlobatarPalette.swift.

package moscow.swag.iskra.design.blobatar

import androidx.compose.ui.graphics.Color

/**
 * Цвет в OKLCh. Палитра задана здесь, а не в sRGB, потому что её гарантия перцептивная: глаз должен
 * читаться на своём теле при любом оттенке, который выпадет сиду.
 */
internal data class BlobatarOklch(val l: Double, val c: Double, val h: Double)

/** Три цвета существа, уже вычисленные. Порт `BlobatarPalette.swift`. */
internal class BlobatarPalette(hue: Double, tone: Double, enforce: Boolean = true) {
    /** Подложка. Рисуется, только когда её просят. */
    val background: BlobatarOklch

    /** Тело и всё, что залито цветом тела. */
    val head: BlobatarOklch

    val eye: BlobatarOklch

    init {
        val swatch = tone(tone)
        var head = ensureContrast(BlobatarOklch(swatch.first, swatch.second, hue), DARK_SURFACE, SURFACE_FLOOR)
        val background = BlobatarOklch(0.965, 0.01, hue)
        // Полярность за телом: тёмные глаза на светлом, светлые на тёмном.
        var eye = if (head.l >= 0.5) BlobatarOklch(0.17, 0.02, hue) else BlobatarOklch(0.97, 0.012, hue)
        if (enforce) {
            // По порядку: вторая пара решается против ответа первой. 4.5 на глазах — порог WCAG для текста.
            head = ensureContrast(head, background, 1.25)
            eye = ensureContrast(eye, head, 4.5)
        }
        this.background = background
        this.head = head
        this.eye = eye
    }

    companion object {
        /** Шесть образцов как накопленные верхние края. Светлота и насыщенность — отсюда. */
        private val TONES = listOf(
            Triple(0.2, 0.86, 0.085), // пастель
            Triple(0.36, 0.9, 0.028), // бледный нейтральный
            Triple(0.62, 0.73, 0.135), // средний
            Triple(0.8, 0.62, 0.165), // глубокий
            Triple(0.93, 0.87, 0.16), // яркий
            // Тёмный, но не темнее тёмной поверхности: при l 0.17 тело пропадало на почти чёрном.
            Triple(1.0, 0.34, 0.035), // чернила
        )

        private fun tone(value: Double): Pair<Double, Double> {
            val found = TONES.firstOrNull { value < it.first } ?: TONES[0]
            return found.second to found.third
        }

        private val DARK_SURFACE = BlobatarOklch(0.145, 0.0, 0.0)
        private const val SURFACE_FLOOR = 1.5

        /** Двигает светлоту, пока пара не наберёт [minimum], — в ту сторону, куда цвет уже клонится. */
        fun ensureContrast(foreground: BlobatarOklch, background: BlobatarOklch, minimum: Double): BlobatarOklch {
            if (contrast(foreground, background) >= minimum) return foreground
            val lean = if (foreground.l >= background.l) 1.0 else -1.0
            for (direction in listOf(lean, -lean)) {
                var probe = foreground
                for (step in 0 until 60) {
                    probe = probe.copy(l = minOf(1.0, maxOf(0.0, probe.l + direction * 0.02)))
                    if (contrast(probe, background) >= minimum) return probe
                    if (probe.l == 0.0 || probe.l == 1.0) break
                }
            }
            val black = BlobatarOklch(0.0, 0.0, foreground.h)
            val white = BlobatarOklch(1.0, 0.0, foreground.h)
            return if (contrast(black, background) >= contrast(white, background)) black else white
        }

        fun contrast(a: BlobatarOklch, b: BlobatarOklch): Double {
            val x = luminance(a)
            val y = luminance(b)
            return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
        }

        private fun luminance(color: BlobatarOklch): Double {
            val (r, g, b) = resolve(color)
            return 0.2126 * r + 0.7152 * g + 0.0722 * b
        }

        /** OKLCh → линейный sRGB. Компоненты могут выйти за `[0, 1]` — это и есть «вне гаммы». */
        private fun toLinear(color: BlobatarOklch): Triple<Double, Double, Double> {
            val radians = color.h * Math.PI / 180
            val a = color.c * BlobatarMath.cos(radians)
            val b = color.c * BlobatarMath.sin(radians)

            val l_ = color.l + 0.3963377774 * a + 0.2158037573 * b
            val m_ = color.l - 0.1055613458 * a - 0.0638541728 * b
            val s_ = color.l - 0.0894841775 * a - 1.291485548 * b

            val ll = l_ * l_ * l_
            val mm = m_ * m_ * m_
            val ss = s_ * s_ * s_

            return Triple(
                4.0767416621 * ll - 3.3077115913 * mm + 0.2309699292 * ss,
                -1.2684380046 * ll + 2.6097574011 * mm - 0.3413193965 * ss,
                -0.0041960863 * ll - 0.7034186147 * mm + 1.707614701 * ss,
            )
        }

        private fun inGamut(rgb: Triple<Double, Double, Double>): Boolean =
            listOf(rgb.first, rgb.second, rgb.third).all { it >= -1e-4 && it <= 1 + 1e-4 }

        /** В гамму — **двоичным поиском по насыщенности вниз**, не обрезкой каналов: обрезка сдвигает оттенок. */
        private fun resolve(color: BlobatarOklch): Triple<Double, Double, Double> {
            var rgb = toLinear(color)
            if (!inGamut(rgb)) {
                var lo = 0.0
                var hi = color.c
                repeat(12) {
                    val mid = (lo + hi) / 2
                    if (inGamut(toLinear(BlobatarOklch(color.l, mid, color.h)))) lo = mid else hi = mid
                }
                rgb = toLinear(BlobatarOklch(color.l, lo, color.h))
            }
            fun clamp(v: Double) = minOf(1.0, maxOf(0.0, v))
            return Triple(clamp(rgb.first), clamp(rgb.second), clamp(rgb.third))
        }

        /** Компоненты sRGB с передаточной функцией — то, что квантует `#rrggbb`. */
        fun components(color: BlobatarOklch): Triple<Double, Double, Double> {
            val (r, g, b) = resolve(color)
            fun encode(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * BlobatarMath.pow(v, 1 / 2.4) - 0.055
            return Triple(encode(r), encode(g), encode(b))
        }

        /** `#rrggbb` в точности как пишет blobatar — с округлением JavaScript (половины вверх). */
        fun hex(color: BlobatarOklch): String {
            val (r, g, b) = components(color)
            return "#" + listOf(r, g, b).joinToString("") { "%02x".format(Math.floor(it * 255 + 0.5).toInt()) }
        }

        fun color(color: BlobatarOklch): Color {
            val (r, g, b) = components(color)
            return Color(r.toFloat(), g.toFloat(), b.toFloat())
        }
    }
}

/**
 * Трансцендентные функции в одном месте. Лицо — это и последний бит косинуса: если Android когда-
 * нибудь разойдётся с фикстурой, менять реализацию здесь, а не по всем файлам порта.
 */
internal object BlobatarMath {
    fun sin(x: Double) = StrictMath.sin(x)
    fun cos(x: Double) = StrictMath.cos(x)
    fun asin(x: Double) = StrictMath.asin(x)
    fun pow(x: Double, y: Double) = StrictMath.pow(x, y)
    fun hypot(x: Double, y: Double) = StrictMath.hypot(x, y)
    fun sqrt(x: Double) = StrictMath.sqrt(x)
}
