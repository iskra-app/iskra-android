package moscow.swag.iskra.core.mock

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Картинки и звуки демо-сервера, нарисованные на месте. Порт `MockMedia.swift`.
 *
 * Нарисованы, а не привезены: пачка стоковых фотографий — мегабайты в приложении ради режима,
 * который откроет только проверяющий, а картинка из зерна — та же самая при каждом запуске, и
 * хранить её не нужно. Рисуется без `android.graphics`, своим растром и своим PNG: демо-мир
 * собирается и в JVM-тесте.
 */
internal object MockMedia {
    /** Квадрат для лица или комнаты: градиент в два цвета и диск на нём. */
    fun avatar(hue: Double): ByteArray = render(AVATAR, AVATAR) { canvas ->
        canvas.gradient(top = color(hue, 0.55, 0.95), bottom = color(hue + 0.08, 0.75, 0.70))
        val w = canvas.width.toDouble()
        val h = canvas.height.toDouble()
        canvas.ellipse(w * 0.28, h * 0.22, w * 0.44, h * 0.44, color(hue + 0.5, 0.25, 1.0, alpha = 0.85))
    }

    /** «Фотография» пейзажа: небо, солнце и две гряды холмов. */
    fun photo(hue: Double, width: Int = PHOTO_WIDTH, height: Int = PHOTO_HEIGHT): ByteArray = render(width, height) { canvas ->
        canvas.gradient(top = color(hue, 0.35, 0.98), bottom = color(hue + 0.05, 0.55, 0.85))
        val w = width.toDouble()
        val h = height.toDouble()
        canvas.ellipse(w * 0.66, h * 0.58, h * 0.22, h * 0.22, color(0.12, 0.6, 1.0))
        canvas.hills(base = 0.38, amplitude = 0.08, phase = 0.3, fill = color(hue + 0.3, 0.45, 0.62))
        canvas.hills(base = 0.22, amplitude = 0.06, phase = 1.7, fill = color(hue + 0.33, 0.55, 0.42))
    }

    /** Несколько секунд мягкого двухтонального звука, 16-бит моно WAV, — голосовое, которое играет. */
    fun voice(seconds: Double = 4.0): Pair<ByteArray, List<Int>> {
        val rate = VOICE_RATE
        val count = (rate * seconds).toInt()
        val samples = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val time = i.toDouble() / rate
            val envelope = (sin(time * 2.3) * 0.5 + 0.5) * min(1.0, time * 8) * min(1.0, (seconds - time) * 8)
            val tone = sin(2 * PI * 220 * time) * 0.6 + sin(2 * PI * 330 * time) * 0.4
            samples.putShort((max(-1.0, min(1.0, tone * envelope * 0.5)) * Short.MAX_VALUE).toInt().toShort())
        }

        val header = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        header.putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        header.put("data".toByteArray()).putInt(count * 2)

        // Та же огибающая, сведённая в полосы 0…1024, которые несёт голосовое.
        val waveform = (0 until WAVEFORM_BARS).map { bar ->
            val time = seconds * bar / WAVEFORM_BARS
            ((sin(time * 2.3) * 0.5 + 0.5) * 900 + 100).toInt()
        }
        return (header.array() + samples.array()) to waveform
    }

    // MARK: Рисование

    private class Rgba(val r: Double, val g: Double, val b: Double, val a: Double = 1.0)

    /** Растр RGBA с началом внизу слева — как контекст Core Graphics, чьи координаты здесь повторены. */
    private class Canvas(val width: Int, val height: Int) {
        val pixels = ByteArray(width * height * 4)

        fun blend(x: Int, yFromBottom: Int, color: Rgba) {
            if (x !in 0 until width || yFromBottom !in 0 until height) return
            val row = height - 1 - yFromBottom
            val index = (row * width + x) * 4
            val a = color.a
            mix(index, color.r, a)
            mix(index + 1, color.g, a)
            mix(index + 2, color.b, a)
            val oldA = (pixels[index + 3].toInt() and 0xFF) / 255.0
            pixels[index + 3] = ((a + oldA * (1 - a)) * 255).toInt().coerceIn(0, 255).toByte()
        }

        private fun mix(index: Int, channel: Double, alpha: Double) {
            val old = (pixels[index].toInt() and 0xFF) / 255.0
            pixels[index] = ((channel * alpha + old * (1 - alpha)) * 255).toInt().coerceIn(0, 255).toByte()
        }

        fun gradient(top: Rgba, bottom: Rgba) {
            for (y in 0 until height) {
                val t = if (height > 1) y.toDouble() / (height - 1) else 0.0
                val c = Rgba(bottom.r + (top.r - bottom.r) * t, bottom.g + (top.g - bottom.g) * t, bottom.b + (top.b - bottom.b) * t)
                for (x in 0 until width) blend(x, y, c)
            }
        }

        fun ellipse(x: Double, y: Double, w: Double, h: Double, color: Rgba) {
            val cx = x + w / 2
            val cy = y + h / 2
            for (py in max(0, y.toInt()) until min(height, (y + h).toInt() + 1)) {
                for (px in max(0, x.toInt()) until min(width, (x + w).toInt() + 1)) {
                    val dx = (px + 0.5 - cx) / (w / 2)
                    val dy = (py + 0.5 - cy) / (h / 2)
                    if (dx * dx + dy * dy <= 1) blend(px, py, color)
                }
            }
        }

        /** Холмы: от низа до синусоиды, как путь из `MockMedia.hills`. */
        fun hills(base: Double, amplitude: Double, phase: Double, fill: Rgba) {
            for (px in 0 until width) {
                val step = px.toDouble() / width
                val top = height * (base + amplitude * sin(step * PI * 3 + phase))
                for (py in 0 until min(height, top.toInt())) blend(px, py, fill)
            }
        }
    }

    private fun render(width: Int, height: Int, draw: (Canvas) -> Unit): ByteArray {
        val canvas = Canvas(width, height)
        draw(canvas)
        return png(width, height, canvas.pixels)
    }

    /** PNG из RGBA: заголовок, один IDAT со строками без фильтра, конец. */
    fun png(width: Int, height: Int, rgba: ByteArray): ByteArray {
        val raw = ByteArrayOutputStream(height * (width * 4 + 1))
        for (row in 0 until height) {
            raw.write(0)
            raw.write(rgba, row * width * 4, width * 4)
        }
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater(Deflater.BEST_SPEED)).use { it.write(raw.toByteArray()) }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(8).put(6).put(0).put(0).put(0).array()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", compressed.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val stream = DataOutputStream(out)
        stream.writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        stream.write(typeBytes)
        stream.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        stream.writeInt(crc.value.toInt())
    }

    /** HSB: пространство, в котором «та же картинка, другой цвет» — одно число. */
    private fun color(hue: Double, saturation: Double, brightness: Double, alpha: Double = 1.0): Rgba {
        val sector = ((hue % 1) + 1) % 1 * 6
        val chroma = brightness * saturation
        val second = chroma * (1 - abs(sector % 2 - 1))
        val floor = brightness - chroma
        val (r, g, b) = when (sector.toInt()) {
            0 -> Triple(chroma, second, 0.0)
            1 -> Triple(second, chroma, 0.0)
            2 -> Triple(0.0, chroma, second)
            3 -> Triple(0.0, second, chroma)
            4 -> Triple(second, 0.0, chroma)
            else -> Triple(chroma, 0.0, second)
        }
        return Rgba(r + floor, g + floor, b + floor, alpha)
    }

    private const val AVATAR = 256
    const val PHOTO_WIDTH = 1200
    const val PHOTO_HEIGHT = 800
    private const val VOICE_RATE = 16_000
    private const val WAV_HEADER = 44
    private const val WAVEFORM_BARS = 48
}
