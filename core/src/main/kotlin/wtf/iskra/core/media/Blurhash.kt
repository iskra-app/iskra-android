package wtf.iskra.core.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.withSign

/**
 * Размытая форма картинки в тридцати символах. Порт `Blurhash.swift`.
 *
 * Едет в событии рядом с шириной и высотой, вне зашифрованного вложения: это то, что клиент на том
 * конце рисует, пока сама картинка качается. И это **не** украшение, а требование: SDK отказывается
 * отправлять картинку без него (`BaseImageInfo::try_from` → `MissingField`) с ошибкой
 * `InvalidAttachmentData`, которая о blurhash ничего не говорит.
 *
 * Алгоритм — опубликованный (woltapp/blurhash, MIT): небольшое косинусное преобразование в линейном
 * свете, упакованное в base83. Написан по спецификации, как и на iOS, — восемьдесят строк арифметики
 * не повод для зависимости.
 */
object Blurhash {
    /** Четыре на три — то, что шлют все клиенты Matrix: видно, где свет и лица, но не что это. */
    private const val COMPONENTS_X = 4
    private const val COMPONENTS_Y = 3

    private const val BASE83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    /** Хэш картинки, которую не удалось прочесть: ровный серый. Лучше серое пятно, чем неушедшее фото. */
    const val NEUTRAL = "L6PZfSjE.AyE_3t7t7R**0o#DgR4"

    /** Сторона, до которой картинка уменьшается перед преобразованием: больше — те же тридцать символов. */
    const val SIDE = 64

    /** `pixels` — ARGB построчно, как `Bitmap.getPixels`. */
    fun encode(pixels: IntArray, width: Int, height: Int): String {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return NEUTRAL
        val factors = ArrayList<DoubleArray>(COMPONENTS_X * COMPONENTS_Y)
        for (j in 0 until COMPONENTS_Y) {
            for (i in 0 until COMPONENTS_X) {
                val normalisation = if (i == 0 && j == 0) 1.0 else 2.0
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (y in 0 until height) {
                    val basisY = cos(PI * j * y / height)
                    for (x in 0 until width) {
                        val basis = normalisation * cos(PI * i * x / width) * basisY
                        val pixel = pixels[y * width + x]
                        r += basis * linear(((pixel shr 16) and 0xFF) / 255.0)
                        g += basis * linear(((pixel shr 8) and 0xFF) / 255.0)
                        b += basis * linear((pixel and 0xFF) / 255.0)
                    }
                }
                val scale = 1.0 / (width * height)
                factors.add(doubleArrayOf(r * scale, g * scale, b * scale))
            }
        }

        val dc = factors[0]
        val ac = factors.drop(1)
        val hash = StringBuilder()
        hash.append(encode83((COMPONENTS_X - 1) + (COMPONENTS_Y - 1) * 9, 1))

        // Переменные части — относительно наибольшей, чтобы малые сохранили точность. Без них
        // вовсе (снимок голой стены) наибольшей нет, и делитель был бы нулём.
        val maximum = ac.maxOfOrNull { f -> max(abs(f[0]), max(abs(f[1]), abs(f[2]))) } ?: 0.0
        val quantisedMaximum = max(0, min(82, floor(maximum * 166 - 0.5).toInt()))
        val maximumValue = if (maximum > 0) (quantisedMaximum + 1) / 166.0 else 1.0
        hash.append(encode83(quantisedMaximum, 1))

        hash.append(encode83((sRGB(dc[0]) shl 16) + (sRGB(dc[1]) shl 8) + sRGB(dc[2]), 4))
        for (factor in ac) {
            fun quantise(value: Double) = max(0, min(18, floor(signPow(value / maximumValue, 0.5) * 9 + 9.5).toInt()))
            hash.append(encode83(quantise(factor[0]) * 19 * 19 + quantise(factor[1]) * 19 + quantise(factor[2]), 2))
        }
        return hash.toString()
    }

    /** Сама размытость, маленькой — ARGB построчно; null для хэша, который не разбирается. */
    fun decode(hash: String, width: Int = 32, height: Int = 32): IntArray? {
        if (hash.length < 6 || width <= 0 || height <= 0) return null
        val sizeFlag = decode83(hash, 0, 1) ?: return null
        val quantisedMaximum = decode83(hash, 1, 2) ?: return null
        val countY = sizeFlag / 9 + 1
        val countX = sizeFlag % 9 + 1
        if (hash.length != 4 + 2 * countX * countY) return null
        val dc = decode83(hash, 2, 6) ?: return null

        val maximum = (quantisedMaximum + 1) / 166.0
        val colours = ArrayList<DoubleArray>(countX * countY)
        colours.add(doubleArrayOf(linear((dc shr 16) / 255.0), linear(((dc shr 8) and 255) / 255.0), linear((dc and 255) / 255.0)))
        for (i in 1 until countX * countY) {
            val value = decode83(hash, 4 + i * 2, 6 + i * 2) ?: return null
            fun unquantise(q: Int) = signPow((q - 9) / 9.0, 2.0) * maximum
            colours.add(doubleArrayOf(unquantise(value / (19 * 19)), unquantise((value / 19) % 19), unquantise(value % 19)))
        }

        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (j in 0 until countY) {
                    val basisY = cos(PI * y * j / height)
                    for (i in 0 until countX) {
                        val basis = cos(PI * x * i / width) * basisY
                        val colour = colours[i + j * countX]
                        r += colour[0] * basis
                        g += colour[1] * basis
                        b += colour[2] * basis
                    }
                }
                out[y * width + x] = (0xFF shl 24) or (sRGB(r) shl 16) or (sRGB(g) shl 8) or sRGB(b)
            }
        }
        return out
    }

    private fun encode83(value: Int, length: Int): String {
        val result = StringBuilder()
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            result.append(BASE83[digit])
        }
        return result.toString()
    }

    private fun decode83(hash: String, from: Int, to: Int): Int? {
        var value = 0
        for (index in from until to) {
            val digit = BASE83.indexOf(hash[index])
            if (digit < 0) return null
            value = value * 83 + digit
        }
        return value
    }

    /** sRGB не пропорционален свету: всё выше считается в линейном и возвращается сюда в самом конце. */
    private fun linear(value: Double) = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)

    private fun sRGB(value: Double): Int {
        val v = max(0.0, min(1.0, value))
        val encoded = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1 / 2.4) - 0.055
        return (encoded * 255 + 0.5).toInt()
    }

    private fun signPow(value: Double, exponent: Double) = abs(value).pow(exponent).withSign(value)
}
