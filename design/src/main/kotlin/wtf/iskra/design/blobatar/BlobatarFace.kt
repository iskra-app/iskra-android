//  The Compose side of the blobatar port. The generator it draws is ported from blobatar
//  (https://blobatar.dev), MIT licence, Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of IskraDesign/Blobatar/BlobatarView.swift.

package wtf.iskra.design.blobatar

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate

/**
 * Рисует существо, которым выходит сид. Один `Canvas` и горстка залитых путей — без декодирования
 * картинок и без сети: достаточно дёшево для каждой строки прокручиваемого списка.
 *
 * [seed] — идентификатор, а не имя (см. [Blobatar]).
 */
@Composable
fun BlobatarFace(seed: String, modifier: Modifier = Modifier, backdrop: Blobatar.Backdrop = Blobatar.Backdrop.NONE) {
    val drawing = remember(seed, backdrop) { BlobatarCache.drawing(seed, backdrop) }
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        // Квадрат 100×100 растянут чуть больше места и сдвинут на половину прироста: существо растёт
        // из своей середины, а не из левого верхнего угла. Сами метки не меняются — меняется размер.
        val grown = side * drawing.magnification.toFloat()
        translate((size.width - grown) / 2, (size.height - grown) / 2) {
            for ((path, mark) in drawing.paths(grown / 100f)) drawPath(path, mark.color)
        }
    }
}

/** Существо и размер, в котором его рисовать, — вместе, потому что второе мерится по первому. */
internal class BlobatarDrawing(val figure: Blobatar.Figure, val magnification: Double) {
    private var scale = Float.NaN
    private var cached: List<Pair<androidx.compose.ui.graphics.Path, Blobatar.Mark>> = emptyList()

    /** Пути под один масштаб; список при прокрутке рисует одну и ту же строку много кадров подряд. */
    fun paths(scale: Float): List<Pair<androidx.compose.ui.graphics.Path, Blobatar.Mark>> {
        if (scale != this.scale) {
            cached = figure.marks.map { it.path(scale) to it }
            this.scale = scale
        }
        return cached
    }
}

/**
 * Уже вычисленные существа. blobatar стоит хэша и тригонометрии — ничто, пока список не перерисовывает
 * сорок строк за кадр. Ограничен: долгий сеанс видит много отправителей, а это удобство, не склад.
 */
internal object BlobatarCache {
    /**
     * Насколько существу можно вырасти в отведённом месте: шестая часть сверху ставит его рядом с
     * фотографией, не делая самым громким в списке лиц. Потолок, а не множитель.
     */
    const val FILL = 1.16
    private const val LIMIT = 512

    private val drawings = object : LinkedHashMap<String, BlobatarDrawing>(LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BlobatarDrawing>) = size > LIMIT
    }

    @Synchronized
    fun drawing(seed: String, backdrop: Blobatar.Backdrop): BlobatarDrawing = drawings.getOrPut("$backdrop\u0000$seed") {
        val figure = Blobatar.figure(seed, Blobatar.Options(backdrop = backdrop))
        // Подложка сама заполняет квадрат — существо на ней расти не должно.
        BlobatarDrawing(figure, if (backdrop == Blobatar.Backdrop.NONE) figure.magnification(FILL) else 1.0)
    }
}
