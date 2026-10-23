package wtf.iskra.features.room

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import wtf.iskra.core.media.VideoNote
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * Запись кружка камерой: CameraX, один файл на всю запись (docs/PROMPT.md, 11.4). Порт `VideoNoteRecorder`.
 *
 * - **Фронталка по умолчанию**, откат на заднюю. Переворот — и *во время записи*: запись «постоянная»
 *   (`asPersistentRecording`), камера перепривязывается под ней, файл остаётся одним. На iOS ради этого
 *   пришлось уйти с `AVCaptureMovieFileOutput` на `AVAssetWriter`.
 * - **Превью фронталки зеркалится, записанный файл — нет** (`MIRROR_MODE_OFF`): иначе собеседник читает
 *   надпись на футболке задом наперёд.
 * - Запись начинается, как только камера поднялась, *если* палец ещё на кнопке: камера просыпается
 *   не мгновенно, а зажатие, кончившееся раньше неё, — не кружок (`finish` вернёт null).
 *
 * Запись обрезается до квадрата не здесь, а на отправке (`VideoPreparation.prepareNote`): камера пишет
 * то, что умеет, а кружок — то, что видел человек в круге.
 */
@OptIn(ExperimentalPersistentRecording::class)
internal class VideoNoteRecorder(private val context: Context) {
    private val mutableElapsed = MutableStateFlow<Long?>(null)

    /** Сколько записано, мс; 0 — кнопку зажали, камера ещё просыпается; null — ничего не идёт. */
    val elapsed: StateFlow<Long?> = mutableElapsed.asStateFlow()

    private val mutableFront = MutableStateFlow(true)

    /** Какой камерой пишем: фронтальной ли. */
    val front: StateFlow<Boolean> = mutableFront.asStateFlow()

    private val mutableUnavailable = MutableStateFlow(false)

    /** Камеры нет вовсе — или она не поднялась. Экран говорит об этом словами и закрывается. */
    val unavailable: StateFlow<Boolean> = mutableUnavailable.asStateFlow()

    /** Запись дошла до потолка сама, пока палец (или замок) ещё держит: пора отправлять. */
    var onLimit: (() -> Unit)? = null

    private val executor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var session: Take? = null

    /** Кнопку держат: писать, как только камера будет готова. */
    private var wanted = false

    private class Take(val file: File, val done: CompletableDeferred<Uri?> = CompletableDeferred()) {
        var cancelled = false
        var finishing = false
    }

    /** Зажали кнопку: показать камеру и писать, как поднимется. */
    fun begin() {
        wanted = true
        mutableUnavailable.value = false
        if (mutableElapsed.value == null) mutableElapsed.value = 0L
        startIfReady()
    }

    /**
     * Привязать камеру к лайфциклу экрана и поверхности превью. Зовётся оверлеем, пока он на экране;
     * вернётся, когда камера привязана (или нет — тогда [unavailable]).
     */
    suspend fun bind(owner: LifecycleOwner, view: PreviewView) {
        try {
            // CameraX на устройстве, где объявленной камеры нет, инициализируется вечно, повторяя попытки, —
            // и экран ждал бы чёрный круг без слов. Ограничено, как каждое ожидание.
            val provider = withTimeLimit(PROVIDER_LIMIT) { cameraProvider() }
            this.provider = provider
            this.owner = owner
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val recorder = Recorder.Builder()
                // Кружок рисуется кругом в 200 точек: больше SD камере тянуть незачем.
                .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build()
            val capture = VideoCapture.Builder(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
                .setTargetRotation(view.display?.rotation ?: Surface.ROTATION_0)
                .build()
            this.preview = preview
            this.capture = capture
            if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) mutableFront.value = false
            if (!rebind()) {
                mutableUnavailable.value = true
                return
            }
            startIfReady()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.error(Log.Category.MEDIA, "камера не поднялась: ${Log.describe(error)}")
            mutableUnavailable.value = true
        }
    }

    /** Провайдер камер процесса: его будущее ждётся без guava — слушателем на главном потоке. */
    private suspend fun cameraProvider(): ProcessCameraProvider = suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (continuation.isActive) continuation.resume(future.get())
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }, executor)
    }

    /** Экран с превью ушёл: камеру отпустить. Запись, если шла, не трогаем — её судьба в [finish] / [cancel]. */
    fun unbind() {
        runCatching { provider?.unbindAll() }
        preview = null
        capture = null
        owner = null
    }

    /** Другая камера — и во время записи: файл тот же, под ним меняется то, что в кадре. */
    fun flip() {
        val provider = provider ?: return
        val next = !mutableFront.value
        val selector = if (next) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) return
        mutableFront.value = next
        rebind()
    }

    private fun rebind(): Boolean {
        val provider = provider ?: return false
        val owner = owner ?: return false
        val preview = preview ?: return false
        val capture = capture ?: return false
        val selector = if (mutableFront.value) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        return try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, selector, preview, capture)
            true
        } catch (error: Exception) {
            Log.error(Log.Category.MEDIA, "камера не привязалась: ${Log.describe(error)}")
            false
        }
    }

    // Разрешение на микрофон проверил тот, кто зовёт `begin()`: кнопка не начинает без него.
    @SuppressLint("MissingPermission")
    private fun startIfReady() {
        val capture = capture ?: return
        if (!wanted || recording != null) return
        val directory = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        val take = Take(File(directory, UUID.randomUUID().toString() + EXTENSION))
        session = take
        val output = FileOutputOptions.Builder(take.file).setDurationLimitMillis(VideoNote.RECORDING_LIMIT_MS).build()
        recording = capture.output.prepareRecording(context, output)
            .asPersistentRecording()
            .withAudioEnabled()
            .start(executor) { event -> onEvent(take, event) }
        mutableElapsed.value = 0L
    }

    private fun onEvent(take: Take, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Status ->
                if (session === take && !take.cancelled) mutableElapsed.value = event.recordingStats.recordedDurationNanos / NANOS_PER_MS
            is VideoRecordEvent.Finalize -> {
                // Потолок — не ошибка: файл целый, просто оборвался сам. Остальное — нет записи.
                val usable = !event.hasError() || event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                if (!usable) Log.error(Log.Category.MEDIA, "запись кружка оборвалась: код ${event.error}")
                if (take.cancelled || !usable) take.file.delete()
                take.done.complete(if (usable && !take.cancelled) Uri.fromFile(take.file) else null)
                if (event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED && !take.finishing && !take.cancelled) {
                    onLimit?.invoke()
                }
            }
            else -> Unit
        }
    }

    /**
     * Отпустили (или нажали «отправить»): остановить и отдать файл. Null — нечего отправлять: камера не
     * успела подняться или запись оборвалась.
     */
    suspend fun finish(): Uri? {
        wanted = false
        val take = session
        val current = recording
        if (take == null || current == null) {
            reset()
            return null
        }
        take.finishing = true
        current.stop()
        val uri = try {
            withTimeLimit(FINALIZE_LIMIT) { take.done.await() }
        } finally {
            reset()
        }
        return uri
    }

    /** Выбросить запись: уйти влево, «Отменить», «назад», комнату оставили. */
    fun cancel() {
        wanted = false
        session?.cancelled = true
        recording?.stop()
        // Файл без `Finalize` (камера не успела начать) уберёт сам вызов.
        if (recording == null) session?.file?.delete()
        reset()
    }

    private fun reset() {
        recording = null
        session = null
        wanted = false
        mutableElapsed.value = null
    }

    private companion object {
        /** Совпадает с `Camera.sweep`: при запуске папка подметается. */
        const val DIRECTORY = "captured"
        const val EXTENSION = ".mp4"
        const val NANOS_PER_MS = 1_000_000L
        val FINALIZE_LIMIT = 15.seconds
        val PROVIDER_LIMIT = 10.seconds
    }
}
