package wtf.iskra.features.support

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import wtf.iskra.core.media.MetadataCheck
import wtf.iskra.core.support.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Квадрат в пикселях картинки, отсчёт от левого верхнего угла. */
data class CropSquare(val left: Float, val top: Float, val side: Float) {
    val right: Float get() = left + side
    val bottom: Float get() = top + side
}

/**
 * Где картинка стоит внутри квадрата, из которого режется аватар, и сама вырезка. Порт `AvatarCrop.swift`.
 *
 * Арифметика `AvatarCropper` — отдельно от экрана, чтобы проверять её без экрана. Картинка начинает,
 * заполняя квадрат: короткая сторона от края до края, по центру. Её можно приблизить до [MAX_ZOOM] и
 * двигать — но никогда так далеко, чтобы в квадрате показалось что-то кроме картинки.
 *
 * Уходит сам квадрат, не больше [MAX_SIDE] пикселей на сторону, — свежим JPEG, в котором нет ничего,
 * кроме пикселей: фото прямо из галереи несёт, где и когда его сняли, а аватар — самая публичная
 * картинка человека.
 *
 * [imageWidth]/[imageHeight] — пиксели картинки, уже повёрнутой как надо; [side] — сторона квадрата на
 * экране; [offsetX]/[offsetY] — насколько центр картинки сдвинут от центра квадрата, в тех же единицах.
 */
data class AvatarCrop(
    val imageWidth: Float,
    val imageHeight: Float,
    val side: Float,
    /** 1 — картинка заполняет квадрат; больше — ближе. */
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** Единиц экрана на пиксель картинки при этом приближении. */
    val scale: Float
        get() = if (imageWidth <= 0f || imageHeight <= 0f) 1f else side / min(imageWidth, imageHeight) * zoom

    val displayedWidth: Float get() = imageWidth * scale
    val displayedHeight: Float get() = imageHeight * scale

    /** Сдвиг, удержанный так, чтобы картинка всегда закрывала квадрат. */
    fun clamped(x: Float = offsetX, y: Float = offsetY): Pair<Float, Float> {
        val slackX = max(0f, (displayedWidth - side) / 2)
        val slackY = max(0f, (displayedHeight - side) / 2)
        return x.coerceIn(-slackX, slackX) to y.coerceIn(-slackY, slackY)
    }

    /** Квадрат в пикселях самой картинки. */
    val cropSquare: CropSquare
        get() {
            val (x, y) = clamped()
            val length = side / scale
            val centreX = imageWidth / 2 - x / scale
            val centreY = imageHeight / 2 - y / scale
            return CropSquare(centreX - length / 2, centreY - length / 2, length)
        }

    /**
     * Сторона того, что уйдёт: квадрат, но не больше [MAX_SIDE]. До ближайшего пикселя, а не вниз:
     * в `Float` квадрат в 500 пикселей выходит 499,99997, и вниз это был бы 499.
     */
    val outputSide: Int get() = min(MAX_SIDE, cropSquare.side.roundToInt())

    /** Квадрат, вырезанный из [image] и уменьшенный до [MAX_SIDE], — JPEG без единого байта метаданных. */
    fun render(image: Bitmap): ByteArray? {
        val crop = cropSquare
        val out = outputSide
        if (out < 1) return null
        val cut = Bitmap.createBitmap(out, out, Bitmap.Config.ARGB_8888)
        Canvas(cut).drawBitmap(
            image,
            Rect(crop.left.toInt(), crop.top.toInt(), crop.right.toInt(), crop.bottom.toInt()),
            RectF(0f, 0f, out.toFloat(), out.toFloat()),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        // `Bitmap.compress` метаданных не пишет; проверяется всё равно то, что уйдёт, — как у фото.
        val bytes = ByteArrayOutputStream().use { stream ->
            cut.compress(Bitmap.CompressFormat.JPEG, QUALITY, stream)
            stream.toByteArray()
        }
        cut.recycle()
        if (!MetadataCheck.isClean(bytes)) {
            Log.error(Log.Category.MEDIA, "the avatar would not come out clean — not sent")
            return null
        }
        return bytes
    }

    companion object {
        const val MAX_ZOOM = 4f
        const val MAX_SIDE = 1024
        private const val QUALITY = 90

        fun clampedZoom(zoom: Float): Float = zoom.coerceIn(1f, MAX_ZOOM)

        /**
         * Выбранная картинка — стоящей как надо (`ImageDecoder` переносит поворот из EXIF в пиксели) и
         * не больше, чем кто-то может с пользой приблизить: короткой стороне хватит [MAX_SIDE] при
         * [MAX_ZOOM], а двенадцать мегапикселей целиком память не красят. Читать не на главном потоке.
         */
        fun decode(context: Context, uri: Uri): Bitmap? = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                // Программная: из аппаратной `Canvas` вырезать не может.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                val limit = (MAX_SIDE * MAX_ZOOM).toInt()
                if (longest > limit) {
                    val k = limit.toFloat() / longest
                    decoder.setTargetSize(max(1, (info.size.width * k).toInt()), max(1, (info.size.height * k).toInt()))
                }
            }
        } catch (error: Exception) {
            Log.info(Log.Category.MEDIA, "the picture for the avatar would not decode: ${Log.describe(error)}")
            null
        }
    }
}
