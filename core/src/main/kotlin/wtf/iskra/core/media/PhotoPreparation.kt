package wtf.iskra.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import wtf.iskra.core.support.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Фото, готовое уйти: байты, имя, тип, размер в пикселях и размытость — для `ImageInfo`. */
class PreparedPhoto(val bytes: ByteArray, val filename: String, val mimeType: String, val width: Int, val height: Int, val blurhash: String)

/**
 * Во что фото превращается перед отправкой — по `MediaSettings` (docs/PROMPT.md, 8.3). Порт
 * `MediaPreparation.photo` и `MediaCompressor.photo`.
 *
 * Очистка — это перерисовка: фото декодируется в пиксели и кодируется заново, а `Bitmap.compress`
 * метаданных не пишет. Поворот из EXIF при этом переходит в сами пиксели — флага больше нет, и фото
 * стоит правильно в любом просмотрщике. Прозрачное остаётся PNG (JPEG залил бы прозрачное чёрным),
 * остальное — JPEG: его рисует любой клиент на том конце, HEIC — нет.
 *
 * Последнее, что трогает файл, — `MetadataCheck`, и проверяется ровно то, что уйдёт. Не прошло —
 * null, то есть **не отправлять**, а не «отправить оригинал».
 */
object PhotoPreparation {
    fun prepare(original: ByteArray, filename: String, settings: MediaSettings): PreparedPhoto? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val decodable = bounds.outWidth > 0 && bounds.outHeight > 0

        if (!settings.stripsMetadata && !settings.compresses) {
            return untouched(original, filename, bounds)
        }
        // Чего не декодировать, того и не очистить: отказ, а не оригинал с метаданными.
        if (!decodable) return if (settings.stripsMetadata) null else untouched(original, filename, bounds)

        val side = if (settings.compresses) settings.compression.maxPhotoSide else UNCOMPRESSED_SIDE
        val redrawn = redraw(original, bounds, side) ?: return if (settings.stripsMetadata) null else untouched(original, filename, bounds)
        val transparent = isTransparent(redrawn)
        val quality = if (settings.compresses) settings.compression.photoQuality else UNCOMPRESSED_QUALITY
        val bytes = ByteArrayOutputStream().use { out ->
            redrawn.compress(if (transparent) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        }
        val width = redrawn.width
        val height = redrawn.height
        val blurhash = blurhash(redrawn)
        redrawn.recycle()

        val stem = filename.substringBeforeLast('.').ifBlank { FALLBACK_STEM }
        val prepared = PreparedPhoto(
            bytes,
            stem + if (transparent) PNG_EXTENSION else JPEG_EXTENSION,
            if (transparent) PNG_MIME else JPEG_MIME,
            width,
            height,
            blurhash,
        )

        if (!settings.stripsMetadata) {
            // Метаданные оставить просили: перерисованное их потеряло бы, и сжатие стоило бы места на
            // карте. Сжатое, оказавшееся больше оригинала, — не повод слать большее: уходит оригинал.
            return if (settings.compresses && bytes.size < original.size) withMetadataOf(original, prepared) else
                untouched(original, filename, bounds, blurhash)
        }
        if (!MetadataCheck.isClean(prepared.bytes)) {
            Log.error(Log.Category.MEDIA, "фото не очистилось — не отправляется")
            return null
        }
        return prepared
    }

    /** Оригинал как есть — только когда ни очистки, ни сжатия не просили (или сжатое вышло больше). */
    private fun untouched(original: ByteArray, filename: String, bounds: BitmapFactory.Options, blurhash: String? = null) = PreparedPhoto(
        original,
        filename,
        bounds.outMimeType ?: OCTET_STREAM,
        bounds.outWidth.coerceAtLeast(0),
        bounds.outHeight.coerceAtLeast(0),
        blurhash ?: blurhashOf(original, bounds),
    )

    /** Размытость по уменьшенной копии: хэш двенадцати мегапикселей — тот же, что хэш миниатюры. */
    private fun blurhash(bitmap: Bitmap): String {
        val scale = minOf(1f, Blurhash.SIDE.toFloat() / max(bitmap.width, bitmap.height))
        val width = max(1, (bitmap.width * scale).toInt())
        val height = max(1, (bitmap.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== bitmap) small.recycle()
        return Blurhash.encode(pixels, width, height)
    }

    private fun blurhashOf(original: ByteArray, bounds: BitmapFactory.Options): String {
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Blurhash.NEUTRAL
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Blurhash.SIDE) sample *= 2
        val small = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return Blurhash.NEUTRAL
        return blurhash(small).also { small.recycle() }
    }

    /**
     * Пиксели оригинала, повёрнутые по EXIF и уменьшенные так, чтобы длинная сторона была не больше
     * `side`. Декодирование — сразу с прореживанием: двенадцать мегапикселей ради 1280 точек не
     * разворачиваются в память целиком.
     */
    private fun redraw(original: ByteArray, bounds: BitmapFactory.Options, side: Int): Bitmap? {
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= side) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null

        val exif = runCatching { ExifInterface(ByteArrayInputStream(original)) }.getOrNull()
        val matrix = Matrix()
        exif?.let {
            if (it.isFlipped) matrix.postScale(-1f, 1f)
            if (it.rotationDegrees != 0) matrix.postRotate(it.rotationDegrees.toFloat())
        }
        val scale = minOf(1f, side.toFloat() / max(decoded.width, decoded.height))
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) return decoded
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (result !== decoded) decoded.recycle()
        return result
    }

    /**
     * Есть ли хоть один прозрачный пиксель — а не место для них. Флаг альфа-канала — не ответ:
     * скриншоты и половина экспортов несут непрозрачную от края до края альфу, и по флагу
     * четырёхмегабайтный скриншот остался бы PNG при включённом сжатии.
     */
    private fun isTransparent(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr ALPHA_SHIFT) != OPAQUE }) return true
        }
        return false
    }

    /**
     * Метаданные оригинала — в сжатый JPEG, кроме того, что перестало быть правдой: размеров и
     * поворота, который теперь в пикселях. Только когда очистку выключили сами.
     */
    private fun withMetadataOf(original: ByteArray, prepared: PreparedPhoto): PreparedPhoto {
        if (prepared.mimeType != JPEG_MIME) return prepared
        val source = runCatching { ExifInterface(ByteArrayInputStream(original)) }.getOrNull() ?: return prepared
        val file = java.io.File.createTempFile("photo", JPEG_EXTENSION)
        return try {
            file.writeBytes(prepared.bytes)
            val target = ExifInterface(file)
            for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            target.saveAttributes()
            PreparedPhoto(file.readBytes(), prepared.filename, prepared.mimeType, prepared.width, prepared.height, prepared.blurhash)
        } catch (error: Exception) {
            Log.error(Log.Category.MEDIA, "метаданные не перенеслись: ${Log.describe(error)}")
            prepared
        } finally {
            file.delete()
        }
    }

    /** Что оригинал говорит о себе и что остаётся правдой после перерисовки. */
    private val COPIED_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_LENS_MODEL,
    )

    /** Без сжатия фото всё равно перерисовывается — но в пределах, которые память переживёт. */
    private const val UNCOMPRESSED_SIDE = 4096
    private const val UNCOMPRESSED_QUALITY = 95

    private const val ALPHA_SHIFT = 24
    private const val OPAQUE = 0xFF

    private const val JPEG_MIME = "image/jpeg"
    private const val PNG_MIME = "image/png"
    private const val OCTET_STREAM = "application/octet-stream"
    private const val JPEG_EXTENSION = ".jpg"
    private const val PNG_EXTENSION = ".png"
    private const val FALLBACK_STEM = "photo"
}
