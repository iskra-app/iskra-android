package wtf.iskra.features.room

/**
 * Что человек сделал с фото до отправки (`PhotoEdits`): штрихи, подписи и рамка обрезки — всё в пикселях *текущей* картинки. Поворот
 * переворачивает и картинку, и всё нарисованное на ней ([turned]): иначе штрих остался бы там, где картинки уже нет.
 */
internal data class PhotoEdits(
    val width: Int,
    val height: Int,
    val strokes: List<Stroke> = emptyList(),
    val labels: List<Label> = emptyList(),
    /** Рамка обрезки; null — картинка целиком. */
    val crop: Frame? = null,
) {
    data class Point(val x: Float, val y: Float)
    data class Stroke(val color: Int, val width: Float, val points: List<Point>)
    data class Label(val id: Int, val text: String, val color: Int, val at: Point, val size: Float)
    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    /** Четверть оборота против часовой: точка (x, y) на картинке шириной W встаёт в (y, W − x). */
    fun turned(): PhotoEdits {
        fun Point.turn() = Point(y, width - x)
        return PhotoEdits(
            width = height,
            height = width,
            strokes = strokes.map { it.copy(points = it.points.map(Point::turn)) },
            labels = labels.map { it.copy(at = it.at.turn()) },
            crop = crop?.let { Frame(it.top, width - it.right, it.bottom, width - it.left) },
        )
    }

    /** Рамка, которую покажет редактор: заданная или вся картинка. */
    val frame: Frame get() = crop ?: Frame(0f, 0f, width.toFloat(), height.toFloat())

    /** Размер итога после обрезки, в пикселях. */
    val outputWidth: Int get() = frame.width.toInt().coerceAtLeast(1)
    val outputHeight: Int get() = frame.height.toInt().coerceAtLeast(1)

    companion object {
        /** Меньше этого рамку не стянуть: сторона в пикселях. */
        const val MIN_SIDE = 64f
    }
}

/** За какую часть рамки тянут (`CropHandle`). */
internal enum class CropHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, BODY;

    /** Новая рамка после сдвига пальца на ([dx], [dy]) пикселей картинки; не выходит за [bounds] и не уже [PhotoEdits.MIN_SIDE]. */
    fun move(frame: PhotoEdits.Frame, dx: Float, dy: Float, bounds: PhotoEdits.Frame): PhotoEdits.Frame {
        val min = PhotoEdits.MIN_SIDE
        return when (this) {
            BODY -> {
                val shiftX = dx.coerceIn(bounds.left - frame.left, bounds.right - frame.right)
                val shiftY = dy.coerceIn(bounds.top - frame.top, bounds.bottom - frame.bottom)
                PhotoEdits.Frame(frame.left + shiftX, frame.top + shiftY, frame.right + shiftX, frame.bottom + shiftY)
            }
            TOP_LEFT -> PhotoEdits.Frame(
                (frame.left + dx).coerceIn(bounds.left, frame.right - min),
                (frame.top + dy).coerceIn(bounds.top, frame.bottom - min),
                frame.right, frame.bottom,
            )
            TOP_RIGHT -> PhotoEdits.Frame(
                frame.left,
                (frame.top + dy).coerceIn(bounds.top, frame.bottom - min),
                (frame.right + dx).coerceIn(frame.left + min, bounds.right),
                frame.bottom,
            )
            BOTTOM_LEFT -> PhotoEdits.Frame(
                (frame.left + dx).coerceIn(bounds.left, frame.right - min),
                frame.top,
                frame.right,
                (frame.bottom + dy).coerceIn(frame.top + min, bounds.bottom),
            )
            BOTTOM_RIGHT -> PhotoEdits.Frame(
                frame.left, frame.top,
                (frame.right + dx).coerceIn(frame.left + min, bounds.right),
                (frame.bottom + dy).coerceIn(frame.top + min, bounds.bottom),
            )
        }
    }

    companion object {
        /** Что под пальцем в точке ([x], [y]): угол в пределах [reach] от него, иначе тело рамки, иначе ничего. */
        fun at(x: Float, y: Float, frame: PhotoEdits.Frame, reach: Float): CropHandle? {
            fun near(px: Float, py: Float) = kotlin.math.hypot(x - px, y - py) <= reach
            return when {
                near(frame.left, frame.top) -> TOP_LEFT
                near(frame.right, frame.top) -> TOP_RIGHT
                near(frame.left, frame.bottom) -> BOTTOM_LEFT
                near(frame.right, frame.bottom) -> BOTTOM_RIGHT
                x in frame.left..frame.right && y in frame.top..frame.bottom -> BODY
                else -> null
            }
        }
    }
}
