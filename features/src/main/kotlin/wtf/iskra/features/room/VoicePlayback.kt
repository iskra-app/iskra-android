package wtf.iskra.features.room

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.iskra.core.media.MediaLoader
import wtf.iskra.core.support.Log
import wtf.iskra.core.timeline.Attachment

/**
 * Одно голосовое за раз — на всё приложение, как `AudioPlayback` на iOS: динамик у телефона один,
 * и два голоса поверх друг друга — это когда не слушают ни один.
 *
 * Байты — из памяти (`MediaDataSource`), не из файла: расшифрованная запись чужого голоса на диске
 * пережила бы и плеер, и экран.
 */
internal object VoicePlayback {
    data class State(
        /** Какое сообщение играет или грузится. */
        val messageId: String? = null,
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        /** Сколько пройдено, 0…1, — до чего заливается волна. */
        val progress: Float = 0f,
        val failed: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val scope = MainScope()
    private var player: MediaPlayer? = null
    private var job: Job? = null

    init {
        // Динамик забрали — кружок или видео: голосовое умолкает, а не говорит поверх.
        scope.launch {
            Speaker.owner.collect { owner ->
                val playing = mutableState.value.messageId ?: return@collect
                if (owner != playing) stop()
            }
        }
    }

    /** Нажатие на кнопку голосового: то же — пауза или дальше, другое — сначала, а прежнее умолкает. */
    fun toggle(messageId: String, attachment: Attachment, loader: MediaLoader) {
        val current = player
        if (mutableState.value.messageId == messageId && current != null) {
            if (current.isPlaying) {
                current.pause()
                mutableState.value = mutableState.value.copy(isPlaying = false)
            } else {
                Speaker.claim(messageId)
                current.start()
                mutableState.value = mutableState.value.copy(isPlaying = true)
                tick()
            }
            return
        }
        if (mutableState.value.messageId == messageId && mutableState.value.isLoading) return
        stop()
        Speaker.claim(messageId)
        mutableState.value = State(messageId = messageId, isLoading = true)
        job = scope.launch {
            try {
                val bytes = loader.content(attachment)
                if (mutableState.value.messageId != messageId) return@launch
                play(messageId, bytes)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.MEDIA, "голосовое не загрузилось: ${Log.describe(error)}")
                mutableState.value = State(messageId = messageId, failed = true)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.release()
        player = null
        mutableState.value = State()
    }

    private fun play(messageId: String, bytes: ByteArray) {
        val next = MediaPlayer()
        player = next
        next.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        next.setDataSource(Bytes(bytes))
        next.setOnPreparedListener {
            if (player !== next) return@setOnPreparedListener
            next.start()
            mutableState.value = State(messageId = messageId, isPlaying = true)
            tick()
        }
        next.setOnCompletionListener { if (player === next) stop() }
        next.setOnErrorListener { _, what, extra ->
            Log.error(Log.Category.MEDIA, "голосовое не играется: $what/$extra")
            if (player === next) {
                next.release()
                player = null
                mutableState.value = State(messageId = messageId, failed = true)
            }
            true
        }
        next.prepareAsync()
    }

    /** Волна движется таймером: «всё ещё играет» плеер не сообщает, только «кончилось». */
    private fun tick() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                val current = player ?: break
                if (!current.isPlaying) break
                val duration = current.duration.coerceAtLeast(1)
                mutableState.value = mutableState.value.copy(progress = (current.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                delay(TICK_MS)
            }
        }
    }

    private class Bytes(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, count)
            return count
        }

        override fun getSize(): Long = bytes.size.toLong()
        override fun close() = Unit
    }

    private const val TICK_MS = 50L
}
