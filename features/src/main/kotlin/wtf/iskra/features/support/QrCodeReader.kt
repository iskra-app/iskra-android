package wtf.iskra.features.support

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Читает QR-код из кадра камеры или из картинки. ZXing — только декодер: ни ML Kit, ни сервисов Google, кадр никуда не
 * уходит (docs/PROMPT.md, 5). Читает *всё*, что похоже на QR: что это за текст, решает вызывающий (`MatrixLink.parse`).
 */
internal object QrCodeReader {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * Текст кода в кадре камеры. [luma] — плоскость яркости (Y) с шагом [rowStride]: шаг бывает шире кадра, и строки
     * переписываются в плотный буфер. Поворот кадра не важен: QR находится в любом.
     */
    fun read(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val dense = if (rowStride == width) {
            luma
        } else {
            ByteArray(width * height).also { out -> for (row in 0 until height) System.arraycopy(luma, row * rowStride, out, row * width, width) }
        }
        return decode(PlanarYUVLuminanceSource(dense, width, height, 0, 0, width, height, false))
    }

    /** Текст кода на картинке; картинка с несколькими — первый найденный. Null — кода нет. */
    fun read(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) }
        return decode(RGBLuminanceSource(width, height, pixels))
    }

    /** Сначала по местным порогам, потом по общему: снимок экрана с экрана читается вторым, бумага с тенью — первым. */
    private fun decode(source: LuminanceSource): String? {
        for (binarizer in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
            try {
                return QRCodeReader().decode(BinaryBitmap(binarizer), hints).text
            } catch (_: NotFoundException) {
                // Нет кода в этом разрезе — пробуем другой.
            } catch (_: com.google.zxing.ChecksumException) {
            } catch (_: com.google.zxing.FormatException) {
            }
        }
        return null
    }
}
