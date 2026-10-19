package wtf.iskra.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.Mp4OrientationData
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.common.util.Clock
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/** Кадр, который едет рядом с видео, чтобы у получателя было что показать до самого видео. */
class VideoThumbnail(val jpeg: ByteArray, val width: Int, val height: Int)

/**
 * Видео, готовое уйти: файл (копия — её удаляет отправитель, когда загрузка кончилась), размеры уже
 * с поворотом, длительность, кадр и размытость — для `VideoInfo`.
 */
class PreparedVideo(
    val file: File,
    val filename: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val thumbnail: VideoThumbnail?,
    val blurhash: String,
)

/**
 * Во что видео превращается перед отправкой — по `MediaSettings`. Порт `MediaPreparation.video` и
 * `VideoTranscoder` на Media3 Transformer.
 *
 * - **Сжатие** — H.264 и AAC в MP4 (играет в любом клиенте Matrix, веб тоже), в рамку шага, с потолком
 *   битрейта и кадров (`MediaCompression`). Не вышло или вышло *больше* исходного (ролик уже мал или
 *   уже пережат) — не повод отказать: уходит без сжатия.
 * - **Очистка** — новый контейнер (перепаковка без перекодирования, если сжимать не просили), в
 *   который мультиплексор не пишет ничего, кроме поворота: ни места, ни дат, ни устройства. И
 *   [Mp4MetadataCheck] проверяет готовый файл: не чист — не уходит, как фото.
 *
 * Копия — открытый текст на диске, в [directory]: живёт, пока идёт загрузка, и подметается при запуске.
 */
@OptIn(UnstableApi::class)
object VideoPreparation {
    /** Null — очистить не вышло, и видео не уходит. */
    suspend fun prepare(context: Context, source: Uri, filename: String, directory: File, settings: MediaSettings): PreparedVideo? {
        directory.mkdirs()
        val original = withContext(Dispatchers.IO) { probe(context, source) } ?: return null
        val stem = filename.substringBeforeLast('.').ifBlank { FALLBACK_STEM }

        var output: File? = null
        if (settings.compresses) {
            val compressed = File(directory, "${UUID.randomUUID()}.mp4")
            val done = runCatching { transform(context, source, compressed, settings.compression, original) }
            done.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
                Log.error(Log.Category.MEDIA, "видео уйдёт без сжатия: ${Log.describe(error)}")
            }
            if (done.isSuccess && compressed.length() in 1 until original.size) output = compressed else compressed.delete()
        }
        if (output == null) {
            output = File(directory, "${UUID.randomUUID()}.mp4")
            if (settings.stripsMetadata) {
                // Чистый контейнер — перепаковкой, без перекодирования. Не вышло — видео не уходит.
                val done = runCatching { transform(context, source, output, compression = null, original = original) }
                if (done.isFailure) {
                    done.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    Log.error(Log.Category.MEDIA, "видео не отправлено: метаданные не убрались: ${Log.describe(done.exceptionOrNull()!!)}")
                    output.delete()
                    return null
                }
            } else {
                // Метаданные оставить просили и сжимать не надо — уходит как есть.
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(source)?.use { input -> output.outputStream().use { input.copyTo(it) } }
                        ?: error("видео не читается")
                }
            }
        }
        val file = output
        if (settings.stripsMetadata && !withContext(Dispatchers.IO) { Mp4MetadataCheck.isClean(file) }) {
            Log.error(Log.Category.MEDIA, "видео не отправлено: в контейнере остались метаданные")
            file.delete()
            return null
        }
        return withContext(Dispatchers.IO) { describe(file, "$stem.mp4") }
    }

    /** Что известно об исходном ролике: размер файла, рамка, битрейт и кадры — чтобы не раздувать. */
    private class Probe(val size: Long, val width: Int, val height: Int, val bitRate: Int?, val frameRate: Float?)

    private fun probe(context: Context, source: Uri): Probe? = runCatching {
        val size = context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, source)
            Probe(
                size = if (size > 0) size else Long.MAX_VALUE,
                width = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0,
                height = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0,
                bitRate = retriever.number(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull(),
            )
        }
    }.onFailure { Log.error(Log.Category.MEDIA, "видео не читается: ${Log.describe(it)}") }.getOrNull()

    /**
     * Перекодирует ([compression] не null) или перепаковывает видео в [target]. Transformer живёт на
     * потоке с Looper — главном; ограничено щедро: телефон перекодирует быстрее реального времени,
     * и до предела доходит только застрявшее.
     */
    private suspend fun transform(context: Context, source: Uri, target: File, compression: MediaCompression?, original: Probe) {
        val effects = mutableListOf<Effect>()
        var encoders: DefaultEncoderFactory? = null
        if (compression != null) {
            fitting(original.width, original.height, compression)?.let { (w, h) ->
                effects += Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
            }
            effects += FrameDropEffect.createDefaultFrameDropEffect(compression.maxFrameRate.toFloat())
            // Потолок, а не цель: ролик, который тратит меньше, сохраняет свой битрейт.
            val bitRate = original.bitRate?.let { min(it, compression.videoBitRate) } ?: compression.videoBitRate
            encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitRate).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(compression.audioBitRate).build())
                .build()
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(emptyList(), effects))
            .build()
        val limit = (EXPORT_FLOOR_SECONDS + EXPORT_PER_MB_SECONDS * (original.size / MEGABYTE).coerceAtMost(MAX_MB_FOR_LIMIT)).seconds
        withTimeLimit(limit) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val builder = Transformer.Builder(context)
                        // Не поднялся аппаратный декодер — следующий, вплоть до программного, как у плеера.
                        .setAssetLoaderFactory(
                            DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build(), Clock.DEFAULT, null),
                        )
                        // Только поворот: место (`Mp4LocationData`), даты (`Mp4TimestampData`), XMP и ключи
                        // устройства (`MdtaMetadataEntry`) в новый контейнер не попадают.
                        .setMuxerFactory(InAppMp4Muxer.Factory { entries -> entries.retainAll { it is Mp4OrientationData } })
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (continuation.isActive) continuation.resumeWithException(exportException)
                            }
                        })
                    if (compression != null) {
                        builder.setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        encoders?.let(builder::setEncoderFactory)
                    }
                    val transformer = builder.build()
                    continuation.invokeOnCancellation { transformer.cancel() }
                    transformer.start(item, target.absolutePath)
                }
            }
        }
    }

    /** Рамка шага с той же ориентацией, что у ролика; null — ролик в неё уже помещается (не увеличивать). */
    internal fun fitting(width: Int, height: Int, compression: MediaCompression): Pair<Int, Int>? {
        if (width <= 0 || height <= 0) return null
        val (boxW, boxH) = if (width >= height) compression.videoLong to compression.videoShort else compression.videoShort to compression.videoLong
        val scale = min(boxW.toFloat() / width, boxH.toFloat() / height)
        if (scale >= 1f) return null
        // Чётные стороны: H.264 с нечётными не всякий кодировщик возьмёт.
        fun even(side: Float) = max(2, (side.roundToInt() / 2) * 2)
        return even(width * scale) to even(height * scale)
    }

    /** Что SDK хочет знать о готовом видео: размеры с поворотом, длительность, кадр, размытость. */
    private fun describe(file: File, filename: String): PreparedVideo = MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        val rotation = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
        val rawW = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0
        val rawH = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0
        val (width, height) = if (rotation % 180 != 0) rawH to rawW else rawW to rawH
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val frame = runCatching { retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMBNAIL_SIDE, THUMBNAIL_SIDE) }.getOrNull()
        val thumbnail = frame?.let { bitmap ->
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it) }.toByteArray()
            VideoThumbnail(jpeg, bitmap.width, bitmap.height)
        }
        // Без blurhash SDK видео не шлёт — как и фото.
        val blurhash = frame?.let(PhotoPreparation::blurhash) ?: Blurhash.NEUTRAL
        frame?.recycle()
        PreparedVideo(file, filename, MimeTypes.VIDEO_MP4, width, height, duration, thumbnail, blurhash)
    }

    private fun MediaMetadataRetriever.number(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

    /** Папка исходящих видео: живёт, пока идёт загрузка; при запуске подметается целиком. */
    fun outgoing(cacheDir: File): File = File(cacheDir, OUTGOING)

    private const val OUTGOING = "outgoing"

    private const val FALLBACK_STEM = "video"
    private const val THUMBNAIL_SIDE = 800
    private const val THUMBNAIL_QUALITY = 80
    private const val MEGABYTE = 1024L * 1024
    private const val EXPORT_FLOOR_SECONDS = 60L
    private const val EXPORT_PER_MB_SECONDS = 4L
    private const val MAX_MB_FOR_LIMIT = 2_000L
}
