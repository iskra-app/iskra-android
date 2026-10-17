//  Ported from blobatar (https://blobatar.dev), MIT licence,
//  Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of IskraDesign/Blobatar/BlobatarHash.swift.

package moscow.swag.iskra.design.blobatar

import java.text.Normalizer

/**
 * Хэш сида. Порт `BlobatarHash.swift` — **та же программа**, а не похожая (docs/PROMPT.md, 6.6).
 *
 * Два свойства — вся причина, по которой это не `hashCode` и не SHA-256: лавина («boris» и «borys» —
 * заметно разные существа) и поток (сид хэшируется раз, каждая черта продолжает с этого состояния
 * со своим именем, так что добавленная позже черта не трогает уже существующие лица).
 *
 * Воспроизведён в точности, включая то, что выглядит случайностью: `state` подмешивает длину строки
 * в **UTF-16**, а байты потом — **UTF-8**, как в JavaScript. Matrix ID — ASCII, и для него это
 * совпадает, но фикстура полна «🦊6🌱», и решает фикстура.
 *
 * Арифметика — `UInt`: умножение в Kotlin оборачивается по модулю 2³², как `&*` в Swift.
 */
internal object BlobatarHash {
    private const val PRIME: UInt = 3_432_918_353u
    private const val SEED: UInt = 1_779_033_703u
    private const val FMIX1: UInt = 2_246_822_507u
    private const val FMIX2: UInt = 3_266_489_909u
    private const val TWO_32 = 4_294_967_296.0

    /** FNV-1a: xor, потом умножение (на `c1` из murmur3) — и поворот на 13, которого у FNV-1a нет. */
    private fun feed(state: UInt, bytes: ByteArray): UInt {
        var h = state
        for (byte in bytes) {
            h = (h xor (byte.toUInt() and 0xFFu)) * PRIME
            h = (h shl 13) or (h shr 19)
        }
        return h
    }

    /** `fmix32` из murmur3 — биекция на `UInt32` с полной лавиной. */
    private fun finalize(state: UInt): UInt {
        var h = state
        h = (h xor (h shr 16)) * FMIX1
        h = (h xor (h shr 13)) * FMIX2
        return h xor (h shr 16)
    }

    /**
     * NFC, потом обрезка, потом нижний регистр — в этом порядке: «é» составное и разложенное,
     * `@Boris:matrix.org` и `@boris:matrix.org`, имя с пробелом в конце — один человек.
     */
    fun normalize(seed: String): String =
        Normalizer.normalize(seed, Normalizer.Form.NFC).trim().lowercase()

    fun state(seed: String, normalize: Boolean = true): UInt {
        val s = if (normalize) normalize(seed) else seed
        // `length` — это UTF-16, как `utf16.count` в Swift и `.length` в JavaScript.
        return feed(SEED xor s.length.toUInt(), s.toByteArray(Charsets.UTF_8))
    }

    /** Одно равномерное значение в `[0, 1)` для [key], независимое от любого другого ключа. */
    fun stream(state: UInt, key: String): Double {
        val separated = feed(state, byteArrayOf(0xFF.toByte()))
        return finalize(feed(separated, key.toByteArray(Charsets.UTF_8))).toDouble() / TWO_32
    }
}

/**
 * Черты одного существа, по *именам* (`"eye.gap"`, `"body.r3"`), а не из последовательного потока:
 * пространство имён только дополняется, и черта, введённая позже, не сдвигает существующие лица.
 * Заморожено не имя, а диапазон: `num("eye.gap", 0.1, 0.24)` — часть отображения сида в облик.
 */
internal class BlobatarTraits(
    seed: String,
    normalize: Boolean = true,
    /** Фиксированные позиции в `[0, 1)`; список — «любая из них, какая выпадет этому сиду». */
    private val overrides: Map<String, List<Double>> = emptyMap(),
) {
    private val state = BlobatarHash.state(seed, normalize)

    /** Равномерно в `[0, 1)`. */
    operator fun invoke(key: String): Double {
        val options = overrides[key]
        if (options.isNullOrEmpty()) return BlobatarHash.stream(state, key)
        val chosen = options[(BlobatarHash.stream(state, key) * options.size).toInt()]
        // Зажато, а не принято на веру: `int` округляет вниз, так что заданная 1 упала бы за верх
        // диапазона, а NaN через то же сравнение падает вниз.
        if (!(chosen > 0)) return 0.0
        return if (chosen < 1) chosen else 0.999999
    }

    /** Равномерно в `[min, max)`. */
    fun num(key: String, min: Double, max: Double): Double = min + this(key) * (max - min)

    /** Равномерное целое в `[min, max]`. */
    fun int(key: String, min: Int, max: Int): Int = min + (this(key) * (max - min + 1).toDouble()).toInt()

    /** Симметричный разброс в `[-amount, amount)`. */
    fun jitter(key: String, amount: Double): Double = (this(key) * 2 - 1) * amount
}
