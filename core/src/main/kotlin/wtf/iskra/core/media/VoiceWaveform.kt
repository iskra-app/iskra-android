package wtf.iskra.core.media

import kotlin.math.log10

/**
 * Волна голосового: из уровней микрофона — в 128 точек, которые едут в событии (порт арифметики
 * `VoiceRecorder.swift`). Обе стороны рисуют одну и ту же картинку, а не угадывают каждая свою.
 */
object VoiceWaveform {
    /** Столько точек несёт событие. */
    const val SAMPLE_COUNT = 128

    /** Шкала в событии: 0…1024 на точку — то же число, что `TimelineMapper.WAVEFORM_SCALE` на входе. */
    const val WIRE_SCALE = 1024f

    /** Пол: −50 дБ — тихая речь, а не шум комнаты. Выше пола — вся высота столбика. */
    private const val FLOOR_DB = -50f
    private const val FULL_SCALE = 32767f

    /**
     * Уровень по `MediaRecorder.getMaxAmplitude` (0…32767) — в 0…1. Децибелы, а не амплитуда прямо:
     * обычная речь сидит в верхних нескольких децибелах, и линейная волна была бы почти ровной.
     */
    fun level(amplitude: Int): Float {
        if (amplitude <= 0) return 0f
        val decibels = 20f * log10(amplitude / FULL_SCALE)
        if (decibels <= FLOOR_DB) return 0f
        return ((decibels - FLOOR_DB) / -FLOOR_DB).coerceAtMost(1f)
    }

    /**
     * Отсчёты, сжатые в 128 — *средним* по корзине, а не каждым тридцатым: слово между двумя
     * снимками иначе просто пропадало бы. Короткая запись дополняется нулями, а не растягивается.
     */
    fun resampled(samples: List<Float>): List<Float> {
        if (samples.isEmpty()) return List(SAMPLE_COUNT) { 0f }
        if (samples.size <= SAMPLE_COUNT) return samples + List(SAMPLE_COUNT - samples.size) { 0f }
        val bucket = samples.size.toDouble() / SAMPLE_COUNT
        return List(SAMPLE_COUNT) { index ->
            val start = (index * bucket).toInt()
            val end = minOf(samples.size, ((index + 1) * bucket).toInt())
            if (start >= end) samples[minOf(start, samples.size - 1)] else samples.subList(start, end).average().toFloat()
        }
    }

    /** В шкалу события. */
    fun wire(samples: List<Float>): List<Float> = samples.map { it.coerceIn(0f, 1f) * WIRE_SCALE }
}
