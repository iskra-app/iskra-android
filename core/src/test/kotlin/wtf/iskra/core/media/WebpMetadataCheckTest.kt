package wtf.iskra.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Белый список для WebP: файлы собраны байт за байтом, как их оставляют редакторы и камеры. */
class WebpMetadataCheckTest {
    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private fun chunk(type: String, payload: ByteArray): ByteArray =
        type.toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload + if (payload.size % 2 == 1) byteArrayOf(0) else ByteArray(0)

    private fun riff(vararg chunks: ByteArray, tail: ByteArray = ByteArray(0), declaredExtra: Int = 0): ByteArray {
        val body = "WEBP".toByteArray(Charsets.US_ASCII) + chunks.fold(ByteArray(0)) { all, part -> all + part }
        return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size + declaredExtra) + body + tail
    }

    private fun vp8x(flags: Int) = chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 15, 0, 0, 15, 0, 0))
    private val picture = chunk("VP8L", ByteArray(21))

    @Test
    fun `простая картинка — чистая`() {
        assertTrue(MetadataCheck.isClean(riff(picture)))
        assertTrue(MetadataCheck.isWebp(riff(picture)))
    }

    @Test
    fun `прозрачность и цветовой профиль — не метаданные`() {
        assertTrue(MetadataCheck.isClean(riff(vp8x(0x30), chunk("ICCP", ByteArray(8)), chunk("ALPH", ByteArray(4)), chunk("VP8 ", ByteArray(10)))))
    }

    @Test
    fun `EXIF и XMP — грязь`() {
        assertFalse(MetadataCheck.isClean(riff(vp8x(0x08), picture, chunk("EXIF", "GPS 55.75 37.59".toByteArray()))))
        assertFalse(MetadataCheck.isClean(riff(vp8x(0x04), picture, chunk("XMP ", "<x:xmpmeta/>".toByteArray()))))
    }

    @Test
    fun `флаг EXIF в заголовке без самого чанка — тоже не чисто`() {
        assertFalse(MetadataCheck.isClean(riff(vp8x(0x08), picture)))
    }

    @Test
    fun `неизвестный чанк и хвост — грязь`() {
        assertFalse(MetadataCheck.isClean(riff(picture, chunk("XXXX", ByteArray(4)))))
        assertFalse(MetadataCheck.isClean(riff(picture, tail = byteArrayOf(1, 2, 3, 4))))
    }

    @Test
    fun `размер в заголовке не сходится с файлом — битый`() {
        assertFalse(MetadataCheck.isClean(riff(picture, declaredExtra = 4)))
        assertFalse(MetadataCheck.isClean(riff(picture).copyOf(20)))
    }

    @Test
    fun `кадры анимации проверяются изнутри`() {
        val frameHeader = ByteArray(16)
        assertTrue(MetadataCheck.isClean(riff(vp8x(0x02), chunk("ANIM", ByteArray(6)), chunk("ANMF", frameHeader + picture))))
        assertFalse(MetadataCheck.isClean(riff(vp8x(0x02), chunk("ANIM", ByteArray(6)), chunk("ANMF", frameHeader + picture + chunk("EXIF", ByteArray(4))))))
    }
}
