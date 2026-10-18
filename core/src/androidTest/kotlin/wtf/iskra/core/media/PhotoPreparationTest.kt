package wtf.iskra.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Фото с телефона — с местом, датой, камерой и поворотом в EXIF — проходит подготовку на настоящем
 * `Bitmap` (docs/PROMPT.md, 8.3: «тесты с файлами, *в которых* есть GPS»). На JVM хоста этого не
 * проверить: там нет ни декодера, ни кодировщика.
 */
@RunWith(AndroidJUnit4::class)
class PhotoPreparationTest {
    /** 3000 × 2000 в файле, поворот 90°: на самом деле это вертикальное фото 2000 × 3000. */
    private fun phonePhoto(): ByteArray {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(40, 120, 200))
            drawCircle(2200f, 600f, 300f, Paint().apply { color = Color.YELLOW })
        }
        val jpeg = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it); it.toByteArray() }
        val file = File.createTempFile("phone", ".jpg")
        try {
            file.writeBytes(jpeg)
            ExifInterface(file).apply {
                setLatLong(55.7575, 37.5979)
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:18 12:00:00")
                setAttribute(ExifInterface.TAG_MAKE, "Phone")
                setAttribute(ExifInterface.TAG_MODEL, "Phone 9 Pro")
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun exif(bytes: ByteArray) = ExifInterface(ByteArrayInputStream(bytes))

    @Test
    fun fixtureIsDirty() {
        val original = phonePhoto()
        assertNotNull(exif(original).latLong)
        assertFalse(MetadataCheck.isClean(original))
    }

    @Test
    fun defaultsStripCompressAndRotate() {
        val original = phonePhoto()
        val sent = assertNotNull(PhotoPreparation.prepare(original, "IMG_0042.JPG", MediaSettings.DEFAULT))
        assertTrue(MetadataCheck.isClean(sent.bytes))
        assertNull(exif(sent.bytes).latLong)
        assertNull(exif(sent.bytes).getAttribute(ExifInterface.TAG_MODEL))
        assertEquals("image/jpeg", sent.mimeType)
        assertEquals("IMG_0042.jpg", sent.filename)
        // Поворот в пикселях: вертикальное, длинная сторона — по шагу «средне».
        assertTrue(sent.width < sent.height, "${sent.width}×${sent.height}")
        assertTrue(maxOf(sent.width, sent.height) <= MediaCompression.BALANCED.maxPhotoSide)
        val decoded = BitmapFactory.decodeByteArray(sent.bytes, 0, sent.bytes.size)
        assertEquals(sent.width, decoded.width)
        assertTrue(sent.bytes.size < original.size)
        assertEquals(28, sent.blurhash.length)
    }

    @Test
    fun strippingAloneIsClean() {
        val sent = assertNotNull(PhotoPreparation.prepare(phonePhoto(), "a.jpg", MediaSettings(stripsMetadata = true, compresses = false, compression = MediaCompression.STRONG)))
        assertTrue(MetadataCheck.isClean(sent.bytes))
        assertEquals(2000, sent.width)
        assertEquals(3000, sent.height)
    }

    @Test
    fun compressionKeepsMetadataWhenAsked() {
        val sent = assertNotNull(PhotoPreparation.prepare(phonePhoto(), "a.jpg", MediaSettings(stripsMetadata = false, compresses = true, compression = MediaCompression.STRONG)))
        assertNotNull(exif(sent.bytes).latLong)
        assertEquals(MediaCompression.STRONG.maxPhotoSide, maxOf(sent.width, sent.height))
    }

    @Test
    fun neitherLeavesBytesAlone() {
        val original = phonePhoto()
        val sent = assertNotNull(PhotoPreparation.prepare(original, "a.jpg", MediaSettings(stripsMetadata = false, compresses = false, compression = MediaCompression.STRONG)))
        assertTrue(original.contentEquals(sent.bytes))
    }

    @Test
    fun onlyRealTransparencyStaysPng() {
        fun png(transparent: Boolean): ByteArray {
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (transparent) Color.TRANSPARENT else Color.WHITE)
            Canvas(bitmap).drawCircle(200f, 150f, 100f, Paint().apply { color = Color.RED })
            return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        }
        assertEquals("image/png", PhotoPreparation.prepare(png(true), "s.png", MediaSettings.DEFAULT)?.mimeType)
        assertEquals("image/jpeg", PhotoPreparation.prepare(png(false), "s.png", MediaSettings.DEFAULT)?.mimeType)
    }

    @Test
    fun unreadableIsRefused() {
        assertNull(PhotoPreparation.prepare("не картинка".toByteArray(), "a.jpg", MediaSettings.DEFAULT))
    }
}
