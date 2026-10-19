package wtf.iskra.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Проверка MP4 перед отправкой: что выдаёт место, устройство или XMP — и что разбирается вовсе. */
class Mp4MetadataCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun box(type: String, vararg children: ByteArray, payload: ByteArray = ByteArray(0)): ByteArray {
        val body = children.fold(payload) { acc, child -> acc + child }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(8 + body.size)
            writeBytes(type)
            write(body)
        }
        return out.toByteArray()
    }

    private fun file(vararg boxes: ByteArray) = folder.newFile().apply { writeBytes(boxes.fold(ByteArray(0)) { acc, b -> acc + b }) }

    private val ftyp = box("ftyp", payload = "isom".toByteArray() + ByteArray(4) + "isommp41".toByteArray())
    private val mdat = box("mdat", payload = ByteArray(4096) { it.toByte() })
    private val track = box("trak", box("tkhd", payload = ByteArray(84)))

    @Test
    fun aPlainMovieIsClean() {
        assertTrue(Mp4MetadataCheck.isClean(file(ftyp, box("moov", box("mvhd", payload = ByteArray(100)), track), mdat)))
    }

    @Test
    fun theHeaderAtTheEndIsFoundToo() {
        assertTrue(Mp4MetadataCheck.isClean(file(ftyp, mdat, box("moov", box("mvhd", payload = ByteArray(100)), track))))
    }

    @Test
    fun aLocationInUserDataIsCaught() {
        // `©xyz` — тип с байтом 0xA9, его не записать строкой ASCII.
        val withLocation = byteArrayOf(0, 0, 0, 30, 0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()) + "+55.75+037.61/".toByteArray() + ByteArray(8)
        assertFalse(Mp4MetadataCheck.isClean(file(ftyp, box("moov", box("mvhd", payload = ByteArray(100)), box("udta", payload = withLocation)), mdat)))
    }

    @Test
    fun anAppleLocationKeyIsCaught() {
        val keys = box("keys", payload = ByteArray(8) + "mdtacom.apple.quicktime.location.ISO6709".toByteArray())
        assertFalse(Mp4MetadataCheck.isClean(file(ftyp, box("moov", box("mvhd", payload = ByteArray(100)), box("meta", keys)), mdat)))
    }

    @Test
    fun xmpBesideTheMovieIsCaught() {
        val xmp = box("uuid", payload = ByteArray(16) + "<x:xmpmeta xmlns:x='adobe:ns:meta/'>".toByteArray())
        assertFalse(Mp4MetadataCheck.isClean(file(ftyp, box("moov", box("mvhd", payload = ByteArray(100))), xmp, mdat)))
    }

    /** Не разобрался — не отправлять: размер коробки больше файла, или заголовка нет вовсе. */
    @Test
    fun whatDoesNotParseIsNotClean() {
        val broken = byteArrayOf(0x7F, 0, 0, 0) + "moov".toByteArray()
        assertFalse(Mp4MetadataCheck.isClean(file(ftyp, broken)))
        assertFalse(Mp4MetadataCheck.isClean(file(ftyp, mdat)))
    }
}
