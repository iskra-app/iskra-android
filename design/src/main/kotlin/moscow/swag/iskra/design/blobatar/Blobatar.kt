//  Ported from blobatar (https://blobatar.dev), MIT licence,
//  Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of IskraDesign/Blobatar/Blobatar.swift and BlobatarRender.swift.

package moscow.swag.iskra.design.blobatar

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * Существо из строки. Тот же сид — то же лицо на каждом устройстве, без сети и состояния: поэтому
 * это порт, а не вызов `blobatar.dev/avatar/<seed>` — тот отправлял бы список всех, с кем ты
 * говоришь, третьей стороне, по строке на чат.
 *
 * Сеять *идентификатором*, никогда не именем: имя меняют, а лицо, меняющееся с профилем, — не личность.
 */
object Blobatar {
    /** Подложка за существом. По умолчанию нет — тело и есть blobatar. */
    enum class Backdrop { NONE, SQUARE, CIRCLE, SQUIRCLE }

    data class Options(
        /** `width`/`height` в SVG. `viewBox` масштабируется в любом случае. */
        val size: Int? = null,
        val backdrop: Backdrop = Backdrop.NONE,
        /** Фиксирует оттенок в градусах — сид решает только форму. */
        val hue: Double? = null,
        /** Фиксирует тон, позицию в `[0, 1)` по шести образцам. */
        val tone: Double? = null,
        val normalize: Boolean = true,
        val contrast: Boolean = true,
        val title: String? = null,
        /** Фиксированные позиции черт по именам раскладки — `mapOf("shape" to listOf(0.96))`. */
        val traits: Map<String, List<Double>> = emptyMap(),
    )

    /** Одна заливка в порядке рисования: подложка, тело (лепестки, добавки, контур), глаза сверху. */
    class Mark internal constructor(internal val outline: BlobatarOutline?, internal val circle: BlobatarPetal?, val color: Color) {
        /** Путь в квадрате `scale × 100`. */
        fun path(scale: Float): Path {
            circle?.let { petal ->
                val r = (BlobatarOutline.round2(petal.r) * scale).toFloat()
                val x = (BlobatarOutline.round2(petal.cx) * scale).toFloat()
                val y = (BlobatarOutline.round2(petal.cy) * scale).toFloat()
                return Path().apply { addOval(androidx.compose.ui.geometry.Rect(x - r, y - r, x + r, y + r)) }
            }
            return outline!!.path(scale)
        }
    }

    /** Всё, чтобы нарисовать одно существо, в пространстве 100×100. */
    class Figure internal constructor(
        /** «round», «droplet», «triangle» — для тестов; человеку это слово не показывается. */
        val shape: String,
        internal val layout: BlobatarLayout,
        internal val palette: BlobatarPalette,
        internal val backdrop: BlobatarOutline?,
    ) {
        /**
         * Каждая заливка отдельно, а не объединённый путь: объединение и так рисует перекрытие, а
         * один путь решал бы обходом, добавляет подпуть к телу или вырезает в нём дыру.
         */
        val marks: List<Mark> by lazy {
            val head = BlobatarPalette.color(palette.head)
            buildList {
                backdrop?.let { add(Mark(it, null, BlobatarPalette.color(palette.background))) }
                layout.petals.forEach { add(Mark(null, it, head)) }
                layout.extra.forEach { add(Mark(it, null, head)) }
                add(Mark(layout.outline, null, head))
                val eye = BlobatarPalette.color(palette.eye)
                eyeOutlines().forEach { add(Mark(it, null, eye)) }
            }
        }

        internal fun eyeOutlines() = layout.eyes.map { BlobatarGeometry.superellipse(it.cx, it.cy, it.rx, it.ry, it.n, it.rot) }

        /**
         * Во сколько раз больше своего квадрата 100×100 существо можно нарисовать. Тело — 31…38 из
         * полуширины 50, так что рядом с фотографиями существо читалось бы «аватаркой поменьше».
         * Растёт, пока не упрётся в [ceiling] или в край своего квадрата, — ничего не обрезается.
         */
        fun magnification(ceiling: Double): Double {
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            fun include(x: Double, y: Double) {
                minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
            backdrop?.points()?.forEach { include(it.first, it.second) }
            layout.petals.forEach {
                val r = BlobatarOutline.round2(it.r)
                include(BlobatarOutline.round2(it.cx) - r, BlobatarOutline.round2(it.cy) - r)
                include(BlobatarOutline.round2(it.cx) + r, BlobatarOutline.round2(it.cy) + r)
            }
            (layout.extra + layout.outline + eyeOutlines()).forEach { outline -> outline.points().forEach { include(it.first, it.second) } }
            if (minX > maxX) return 1.0
            val reach = maxOf(50 - minX, 50 - minY, maxX - 50, maxY - 50)
            if (reach <= 0) return 1.0
            return minOf(ceiling, 50 / reach)
        }
    }

    fun figure(seed: String, options: Options = Options()): Figure {
        val traits = BlobatarTraits(seed, options.normalize, options.traits)
        val layout = BlobatarLayout.make(traits)
        val palette = BlobatarPalette(
            hue = options.hue ?: traits.num("hue", 0.0, 360.0),
            tone = options.tone ?: traits("tone"),
            enforce = options.contrast,
        )
        return Figure(layout.shape, layout, palette, plate(options.backdrop))
    }

    private fun plate(backdrop: Backdrop): BlobatarOutline? = when (backdrop) {
        Backdrop.NONE -> null
        Backdrop.SQUARE -> BlobatarOutline(
            listOf(
                BlobatarCommand.Move(0.0, 0.0), BlobatarCommand.Horizontal(100.0), BlobatarCommand.Vertical(100.0),
                BlobatarCommand.Horizontal(0.0), BlobatarCommand.Close,
            ),
        )
        Backdrop.CIRCLE, Backdrop.SQUIRCLE ->
            BlobatarGeometry.superellipse(50.0, 50.0, 50.0, 50.0, if (backdrop == Backdrop.CIRCLE) 2.0 else 6.0)
    }

    /**
     * SVG байт в байт как у самого blobatar. Приложение его не рисует — только фикстура сверяется,
     * и это единственный способ сделать «та же программа» утверждением, а не впечатлением.
     */
    internal fun svg(seed: String, options: Options = Options()): String {
        val figure = figure(seed, options)
        val layout = figure.layout
        val format = BlobatarOutline::format
        val dimensions = options.size?.let { " width=\"$it\" height=\"$it\"" }.orEmpty()
        val title = options.title?.let { "<title>${escape(it)}</title>" }.orEmpty()
        val plate = figure.backdrop?.let { "<path d=\"${it.pathData}\" fill=\"${BlobatarPalette.hex(figure.palette.background)}\"/>" }.orEmpty()
        val petals = layout.petals.joinToString("") { "<circle cx=\"${format(it.cx)}\" cy=\"${format(it.cy)}\" r=\"${format(it.r)}\"/>" }
        val extra = layout.extra.joinToString("") { "<path d=\"${it.pathData}\"/>" }
        val eyes = figure.eyeOutlines().joinToString("") { "<path d=\"${it.pathData}\"/>" }
        val body = "<g fill=\"${BlobatarPalette.hex(figure.palette.head)}\">" + petals + extra +
            "<path d=\"${layout.outline.pathData}\"/></g>" +
            "<g fill=\"${BlobatarPalette.hex(figure.palette.eye)}\">" + eyes + "</g>"
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\"" + dimensions + ">" + title + plate + body + "</svg>"
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
