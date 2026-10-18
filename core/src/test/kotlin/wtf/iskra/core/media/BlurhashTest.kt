package wtf.iskra.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Порт `BlurhashTests.swift`. Не о том, красива ли размытость: о том, что хэш есть, он той формы,
 * которой требует формат, и правда описывает картинку, а не стоит константой.
 */
class BlurhashTest {
    private fun solid(red: Double, green: Double, blue: Double, width: Int = 60, height: Int = 45): IntArray {
        val pixel = (0xFF shl 24) or ((red * 255).roundToInt() shl 16) or ((green * 255).roundToInt() shl 8) or (blue * 255).roundToInt()
        return IntArray(width * height) { pixel }
    }

    @Test
    fun `хэш той длины, что требует формат`() {
        val hash = Blurhash.encode(solid(0.9, 0.2, 0.2), 60, 45)
        assertEquals(6 + 2 * (4 * 3 - 1), hash.length)
        assertTrue(hash.all { it in "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~" })
    }

    @Test
    fun `разные картинки — разные хэши`() {
        val red = Blurhash.encode(solid(0.9, 0.1, 0.1), 60, 45)
        val blue = Blurhash.encode(solid(0.1, 0.1, 0.9), 60, 45)
        val white = Blurhash.encode(solid(1.0, 1.0, 1.0), 60, 45)
        assertNotEquals(red, blue)
        assertNotEquals(red, white)
        assertNotEquals(blue, white)
    }

    /** Средний цвет — первое в хэше; потеряй одно из преобразований света — и серый выйдет не тот. */
    @Test
    fun `ровный цвет в хэше — цвет картинки`() {
        for ((r, g, b) in listOf(Triple(0.5, 0.5, 0.5), Triple(0.9, 0.2, 0.2), Triple(0.0, 0.0, 0.0), Triple(1.0, 1.0, 1.0))) {
            val hash = Blurhash.encode(solid(r, g, b), 60, 45)
            val packed = hash.substring(2, 6).fold(0) { value, c -> value * 83 + DIGITS.indexOf(c) }
            val actual = listOf((packed shr 16) and 255, (packed shr 8) and 255, packed and 255)
            val expected = listOf(r, g, b).map { (it * 255).roundToInt() }
            actual.zip(expected).forEach { (was, wanted) -> assertTrue("$was вместо $wanted", abs(was - wanted) <= 2) }
        }
    }

    private companion object {
        const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    }

    @Test
    fun `нечитаемое — нейтральный хэш, не пустота`() {
        assertEquals(Blurhash.NEUTRAL, Blurhash.encode(IntArray(0), 0, 0))
        assertTrue(Blurhash.decode(Blurhash.NEUTRAL) != null)
        assertEquals(null, Blurhash.decode("коротко"))
    }
}
