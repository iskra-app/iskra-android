package wtf.iskra.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Белый список метаданных. Файлы собраны здесь байт за байтом — такими, какими их оставляет
 * телефон: с EXIF и GPS, с XMP, с комментарием, с хвостом после конца картинки.
 */
class MetadataCheckTest {
    // JPEG

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val jfif = segment(0xE0, "JFIF".toByteArray() + byteArrayOf(0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val dqt = segment(0xDB, ByteArray(65))
    private val sof = segment(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0))
    private val dht = segment(0xC4, ByteArray(20))

    /** Скан с экранированным FF 00 и маркером перезапуска внутри — так выглядят настоящие данные. */
    private val scan = segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)) +
        byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56)

    private fun jpeg(vararg extra: ByteArray, tail: ByteArray = ByteArray(0)): ByteArray =
        extra.fold(soi + jfif) { all, part -> all + part } + dqt + sof + dht + scan + eoi + tail

    private val exifWithGps = segment(
        0xE1,
        "Exif".toByteArray() + byteArrayOf(0, 0) + "MM".toByteArray() + byteArrayOf(0, 42, 0, 0, 0, 8) +
            "GPSLatitude 55.7575 GPSLongitude 37.5979".toByteArray(),
    )

    @Test
    fun `JPEG только с картинкой — чистый`() {
        assertTrue(MetadataCheck.isClean(jpeg()))
    }

    @Test
    fun `цветовой профиль — не метаданные`() {
        assertTrue(MetadataCheck.isClean(jpeg(segment(0xE2, "ICC_PROFILE".toByteArray() + byteArrayOf(0, 1, 1) + ByteArray(32)))))
    }

    @Test
    fun `EXIF с местом — грязный`() {
        assertFalse(MetadataCheck.isClean(jpeg(exifWithGps)))
    }

    @Test
    fun `XMP — тоже APP1, тоже грязный`() {
        assertFalse(MetadataCheck.isClean(jpeg(segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray()))))
    }

    @Test
    fun `IPTC, комментарий и неизвестный APP — грязные`() {
        assertFalse(MetadataCheck.isClean(jpeg(segment(0xED, "Photoshop 3.0".toByteArray()))))
        assertFalse(MetadataCheck.isClean(jpeg(segment(0xFE, "снято на даче".toByteArray()))))
        assertFalse(MetadataCheck.isClean(jpeg(segment(0xE5, ByteArray(4)))))
    }

    @Test
    fun `APP0 не JFIF и APP2 не профиль — грязные`() {
        assertFalse(MetadataCheck.isClean(jpeg(segment(0xE0, "JFXX".toByteArray() + ByteArray(4)))))
        assertFalse(MetadataCheck.isClean(jpeg(segment(0xE2, "MPF".toByteArray() + ByteArray(8)))))
    }

    @Test
    fun `хвост после конца картинки — грязный`() {
        assertFalse(MetadataCheck.isClean(jpeg(tail = soi + exifWithGps + eoi)))
    }

    @Test
    fun `обрезанный и чужой файл — не чистые`() {
        val whole = jpeg()
        assertFalse(MetadataCheck.isClean(whole.copyOf(whole.size - 2)))
        assertFalse(MetadataCheck.isClean("GIF89a".toByteArray()))
        assertFalse(MetadataCheck.isClean(ByteArray(0)))
    }

    // PNG

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        val body = type.toByteArray(Charsets.ISO_8859_1) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }.value
        out.write(byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    private val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(vararg extra: ByteArray, tail: ByteArray = ByteArray(0)): ByteArray =
        extra.fold(signature + chunk("IHDR", ByteArray(13)) + chunk("sRGB", byteArrayOf(0))) { all, part -> all + part } +
            chunk("IDAT", ByteArray(10)) + chunk("IEND", ByteArray(0)) + tail

    @Test
    fun `PNG только с картинкой — чистый`() {
        assertTrue(MetadataCheck.isClean(png()))
    }

    @Test
    fun `PNG с eXIf, текстом или временем — грязный`() {
        assertFalse(MetadataCheck.isClean(png(chunk("eXIf", "MM".toByteArray() + ByteArray(8)))))
        assertFalse(MetadataCheck.isClean(png(chunk("tEXt", "Author\u0000Саша".toByteArray()))))
        assertFalse(MetadataCheck.isClean(png(chunk("iTXt", "XML:com.adobe.xmp".toByteArray()))))
        assertFalse(MetadataCheck.isClean(png(chunk("tIME", ByteArray(7)))))
        assertFalse(MetadataCheck.isClean(png(chunk("caNv", ByteArray(4)))))
    }

    @Test
    fun `PNG с хвостом после IEND — грязный`() {
        assertFalse(MetadataCheck.isClean(png(tail = chunk("tEXt", "GPS".toByteArray()))))
    }
}
