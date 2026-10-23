package wtf.iskra.features.support

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrCodeReaderTest {
    /** Яркость кода, как её отдала бы камера: чёрное — 0, белое — 255, строки с запасом (шаг шире кадра). */
    private fun frame(text: String, size: Int, stride: Int = size): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        return ByteArray(stride * size) { index ->
            val x = index % stride
            val y = index / stride
            if (x < size && matrix.get(x, y)) 0 else 255.toByte()
        }
    }

    @Test
    fun aCodeInACameraFrameIsRead() {
        assertEquals("https://s.iskra.wtf/@boris:matrix.org", QrCodeReader.read(frame("https://s.iskra.wtf/@boris:matrix.org", 400), 400, 400, 400))
    }

    @Test
    fun aFrameWhoseRowsAreWiderThanItIsReadToo() {
        assertEquals("@anya:matrix.org", QrCodeReader.read(frame("@anya:matrix.org", 360, stride = 384), 384, 360, 360))
    }

    @Test
    fun aFrameWithoutACodeIsNothing() {
        assertNull(QrCodeReader.read(ByteArray(200 * 200) { 200.toByte() }, 200, 200, 200))
    }
}
