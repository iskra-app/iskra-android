package wtf.iskra.features.support

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Код с чистой серединой под искру — всё ещё код: камера читает его, пока искры на нём нет. Порт `QRCodeTests`. */
class QrCardTest {
    private val link = "https://s.iskra.wtf/@boris:matrix.org"

    @Test
    fun centreIsOddAndLeavesRoomForTheSpark() {
        val modules = QrCard.modules(link)
        assertEquals(1, modules.centre % 2)
        assertTrue(modules.sparkFraction in 0.2f..0.3f)
        // Середина чиста: ни одного тёмного модуля внутри квадрата.
        val from = (modules.size - modules.centre) / 2
        for (y in from until from + modules.centre) for (x in from until from + modules.centre) assertTrue(!modules.isDark(x, y))
    }

    @Test
    fun codeWithClearedCentreStillReads() {
        val modules = QrCard.modules(link)
        val unit = 8
        val margin = 4
        val side = (modules.size + 2 * margin) * unit
        val pixels = IntArray(side * side) { index ->
            val x = index % side / unit - margin
            val y = index / side / unit - margin
            val dark = x in 0 until modules.size && y in 0 until modules.size && modules.isDark(x, y)
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val text = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))).text
        assertEquals(link, text)
    }
}
