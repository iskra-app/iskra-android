package wtf.iskra.design

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Куда в альбоме встаёт каждая картинка — мозаика Telegram, выведенная только из пропорций.
 * Порт `AlbumMosaic.swift`.
 *
 * Код Telegram читан, не скопирован — он под GPL, — и взята из него идея: альбом — никогда не сетка
 * одинаковых квадратов, которая обрезает все фото под одну форму. Это *ряды*, и каждый ряд такой
 * высоты, чтобы его картинки встали в ширину без обрезки; свобода одна — как разложить картинки по
 * рядам. Поэтому перебираются все разбиения, и остаётся то, что ближе к приятной общей форме —
 * примерно квадрату, — без рядов-щелей и с крупными картинками сверху. Высокое первое фото рядом с
 * двумя-тремя другими получает свою колонку слева: этого не даст ни одно разбиение на ряды.
 *
 * Чистая геометрия — проверяется без рисования (`AlbumMosaicTest`).
 */
object AlbumMosaic {
    /** Прямоугольник в точках: левый верхний угол, ширина, высота. */
    data class Frame(val x: Float, val y: Float, val width: Float, val height: Float) {
        val right: Float get() = x + width
        val bottom: Float get() = y + height
    }

    /**
     * `ratios` — ширина к высоте, по одной на картинку, по порядку. Возвращает по рамке на картинку
     * в коробке шириной `width` и высотой до нижнего края самой низкой.
     */
    fun frames(ratios: List<Float>, width: Float, spacing: Float, maxHeight: Float): List<Frame> {
        if (ratios.isEmpty() || width <= 0) return emptyList()
        // Чек или панорама иначе унесли бы свой ряд с собой: щель в сто точек или ряд в экран.
        val clamped = ratios.map { min(max(if (it.isFinite() && it > 0) it else 1f, MIN_RATIO), MAX_RATIO) }

        if (clamped.size == 1) return listOf(Frame(0f, 0f, width, min(width / clamped[0], maxHeight)))

        var best: Pair<Float, List<Frame>>? = null
        fun consider(frames: List<Frame>, penalty: Float) {
            val height = frames.maxOfOrNull { it.bottom } ?: 0f
            val score = abs(height - width * IDEAL_SHAPE) / width + penalty
            if (best == null || score < best!!.first) best = score to frames
        }

        for (split in splits(clamped.size)) {
            consider(rows(split, clamped, width, spacing), penalty(split, clamped, width, spacing))
        }
        if (clamped.size in 2..4 && clamped[0] < TALL_FIRST) {
            column(clamped, width, spacing)?.let { consider(it, 0f) }
        }
        return fitted(best?.second.orEmpty(), maxHeight)
    }

    /** Все способы разрезать `count` картинок по порядку на ряды от одной до `ROW_LIMIT`. */
    fun splits(count: Int): List<List<Int>> {
        if (count <= 0) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
        for (first in 1..min(ROW_LIMIT, count)) {
            for (rest in splits(count - first)) result.add(listOf(first) + rest)
        }
        return result
    }

    private fun heights(split: List<Int>, ratios: List<Float>, width: Float, spacing: Float): List<Float> {
        var start = 0
        return split.map { count ->
            val sum = ratios.subList(start, start + count).sum()
            start += count
            (width - spacing * (count - 1)) / sum
        }
    }

    private fun rows(split: List<Int>, ratios: List<Float>, width: Float, spacing: Float): List<Frame> {
        val heights = heights(split, ratios, width, spacing)
        val frames = mutableListOf<Frame>()
        var y = 0f
        var start = 0
        split.forEachIndexed { row, count ->
            val height = heights[row]
            var x = 0f
            for (index in start until start + count) {
                // Последняя в ряду забирает остаток от округления: правый край ровный.
                val tile = if (index == start + count - 1) width - x else ratios[index] * height
                frames.add(Frame(x, y, tile, height))
                x += tile + spacing
            }
            y += height + spacing
            start += count
        }
        return frames
    }

    /**
     * Что делает разбиение хуже, чем говорит его форма: ряд тонкий настолько, что читается полоской,
     * и ряд с *большим* числом картинок, чем под ним, — первые фото набора те, на которые смотрят.
     */
    private fun penalty(split: List<Int>, ratios: List<Float>, width: Float, spacing: Float): Float {
        var penalty = 0f
        for (height in heights(split, ratios, width, spacing)) if (height < width / THIN_ROW) penalty += 1f
        for ((upper, lower) in split.zipWithNext()) if (upper > lower) penalty += CROWDED_TOP
        // Одинокая картинка ниже первого ряда — картинка, вынутая из набора. Наверху это обложка.
        if (ratios.size > 2) penalty += LONE_BELOW * split.drop(1).count { it == 1 }
        return penalty
    }

    /** Первая картинка — колонкой слева, остальные стопкой рядом: для высокого первого фото. */
    private fun column(ratios: List<Float>, width: Float, spacing: Float): List<Frame>? {
        val rest = ratios.drop(1)
        val inverse = rest.sumOf { 1.0 / it }.toFloat()
        val stacked = spacing * (rest.size - 1)
        // Ширина колонки, при которой она и стопка выходят одной высоты.
        var left = ((width - spacing) * inverse + stacked) / (1 / ratios[0] + inverse)
        left = min(max(left, width * COLUMN_MIN), width * COLUMN_MAX)
        val right = width - spacing - left
        val height = left / ratios[0]

        // Стопка делит высоту колонки пропорционально тому, чего хочет каждая картинка.
        val wanted = rest.map { right / it }
        val scale = (height - stacked) / wanted.sum()
        if (scale <= 0) return null

        val frames = mutableListOf(Frame(0f, 0f, left, height))
        var y = 0f
        wanted.forEachIndexed { index, want ->
            val tile = if (index == wanted.size - 1) height - y else want * scale
            frames.add(Frame(left + spacing, y, right, tile))
            y += tile + spacing
        }
        return frames
    }

    /** Выше `maxHeight` — сжать по вертикали: картинки чуть обрезаются, а не альбом уезжает за экран. */
    private fun fitted(frames: List<Frame>, maxHeight: Float): List<Frame> {
        val height = frames.maxOfOrNull { it.bottom } ?: 0f
        if (height <= maxHeight || height <= 0) return frames
        val scale = maxHeight / height
        return frames.map { Frame(it.x, it.y * scale, it.width, it.height * scale) }
    }

    /** Высота к ширине, к которой тянутся разбиения. */
    private const val IDEAL_SHAPE = 1f
    private const val ROW_LIMIT = 4
    private const val MIN_RATIO = 0.5f
    private const val MAX_RATIO = 2.5f
    private const val TALL_FIRST = 0.8f
    private const val THIN_ROW = 4f
    private const val CROWDED_TOP = 0.2f
    private const val LONE_BELOW = 0.1f
    private const val COLUMN_MIN = 0.4f
    private const val COLUMN_MAX = 0.75f
}
