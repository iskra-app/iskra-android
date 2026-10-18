package wtf.iskra.features.room

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.iskra.core.media.VoiceWaveform
import wtf.iskra.core.support.Log
import java.io.File
import java.util.UUID

/** Записанное голосовое: байты файла, длительность и волна в 128 точек (0…1). */
internal class Recording(val bytes: ByteArray, val filename: String, val durationMs: Long, val waveform: List<Float>)

/**
 * Запись голосового — как на iOS (docs/PROMPT.md, 11.3): AAC в MPEG-4, 24 кГц, моно, 48 кбит/с —
 * голос, а не музыка. Источник `VOICE_RECOGNITION`: без автоусиления звонка. Уровень — каждые 50 мс,
 * из него волна.
 *
 * Файл записи — открытый текст на диске, пока идёт запись (`cache/recording`): читается и удаляется
 * сразу по окончании; что пережило падение, подметает запуск (`FileOpener.sweepOnLaunch`).
 */
internal class VoiceRecorder(private val context: Context) {
    private val mutableElapsed = MutableStateFlow<Long?>(null)

    /** Сколько записано, мс; null — запись не идёт. */
    val elapsed: StateFlow<Long?> = mutableElapsed.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var ticker: Job? = null
    private val samples = mutableListOf<Float>()
    private var startedAt = 0L

    fun start(scope: CoroutineScope): Boolean {
        if (recorder != null) return true
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val output = File(directory, UUID.randomUUID().toString() + EXTENSION)
        return try {
            val next = recorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(output)
                prepare()
                start()
            }
            recorder = next
            file = output
            samples.clear()
            startedAt = System.currentTimeMillis()
            mutableElapsed.value = 0L
            ticker = scope.launch {
                while (true) {
                    delay(TICK_MS)
                    val current = recorder ?: break
                    samples.add(VoiceWaveform.level(runCatching { current.maxAmplitude }.getOrDefault(0)))
                    mutableElapsed.value = minOf(System.currentTimeMillis() - startedAt, MAX_DURATION_MS.toLong())
                }
            }
            true
        } catch (error: Exception) {
            Log.error(Log.Category.MEDIA, "запись не началась: ${Log.describe(error)}")
            output.delete()
            false
        }
    }

    /** Закончить и отдать записанное; слишком короткое — случайное касание, а не сообщение. */
    fun finish(): Recording? {
        val output = file ?: return null
        val duration = System.currentTimeMillis() - startedAt
        stop()
        return try {
            if (duration < MIN_DURATION_MS || !output.exists()) null else
                Recording(output.readBytes(), FILENAME, minOf(duration, MAX_DURATION_MS.toLong()), VoiceWaveform.resampled(samples.toList()))
        } finally {
            output.delete()
        }
    }

    fun cancel() {
        val output = file
        stop()
        output?.delete()
    }

    private fun stop() {
        ticker?.cancel()
        ticker = null
        recorder?.let { runCatching { it.stop() } ; it.release() }
        recorder = null
        file = null
        mutableElapsed.value = null
    }

    /** С контекстом — с API 31; ниже конструктор без него, тот же рекордер. */
    private fun recorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

    companion object {
        const val MIME_TYPE = "audio/mp4"
        private const val DIRECTORY = "recording"
        private const val EXTENSION = ".m4a"
        private const val FILENAME = "voice.m4a"
        private const val SAMPLE_RATE = 24_000
        private const val BIT_RATE = 48_000
        private const val TICK_MS = 50L
        private const val MIN_DURATION_MS = 500L

        /** Пять минут, как на iOS: дальше это уже не голосовое. */
        private const val MAX_DURATION_MS = 300_000
    }
}
