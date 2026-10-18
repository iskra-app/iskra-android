package wtf.iskra.features.room

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import wtf.iskra.core.support.Log
import wtf.iskra.core.timeline.Attachment
import wtf.iskra.features.support.LocalMediaLoader
import java.io.File
import java.util.UUID

/**
 * Кто сейчас говорит. Динамик у телефона один (`Speaker` на iOS): голосовое, кружок и видео в
 * просмотрщике забирают его друг у друга, и начавшее звучать — единственное, что звучит.
 */
internal object Speaker {
    private val mutableOwner = MutableStateFlow<String?>(null)
    val owner: StateFlow<String?> = mutableOwner.asStateFlow()

    fun claim(id: String) {
        mutableOwner.value = id
    }

    fun release(id: String) {
        mutableOwner.compareAndSet(id, null)
    }
}

/** Видео, как его видит экран: ещё не просили, качается, играет, не вышло. */
internal sealed interface VideoLoad {
    data object Idle : VideoLoad
    data object Loading : VideoLoad
    data class Ready(val player: Player) : VideoLoad
    data object Failed : VideoLoad
}

/**
 * Плеер видео, который появляется, только когда его попросили (`wanted`): лента из сотни видео
 * не качает сотню видео, и пролистнутая страница не тянет файл.
 *
 * Файл — расшифрованный, на диске (`cache/playing`, у каждого видео своя папка): ExoPlayer читает
 * его кусками, а не держит в памяти целиком. Живёт ровно столько, сколько тот, кто позвал: уходит —
 * плеер отпускается и папка удаляется. Что пережило падение, подметает `sweep` при запуске.
 */
// ContentFrame и выбор декодеров Media3 помечены нестабильными: их API ещё меняется между версиями.
@OptIn(UnstableApi::class)
@Composable
internal fun rememberVideo(attachment: Attachment, wanted: Boolean): VideoLoad {
    val context = LocalContext.current
    val loader = LocalMediaLoader.current
    var load by remember(attachment.mediaId) { mutableStateOf<VideoLoad>(VideoLoad.Idle) }
    val holder = remember(attachment.mediaId) { Holder() }

    LaunchedEffect(attachment.mediaId, wanted, loader) {
        if (!wanted || loader == null || load is VideoLoad.Ready || load is VideoLoad.Loading) return@LaunchedEffect
        load = VideoLoad.Loading
        val directory = File(VideoFiles.directory(context), UUID.randomUUID().toString())
        try {
            val file = loader.file(attachment, directory)
            fun start(softwareOnly: Boolean): Player {
                val player = player(context, Uri.fromFile(file), softwareOnly)
                player.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.error(Log.Category.MEDIA, "видео не играется: ${error.errorCodeName}")
                        // Аппаратный декодер сломался посреди файла — ещё раз на программном, один раз.
                        if (!softwareOnly && error.errorCode in DECODING_ERRORS) {
                            holder.swap(start(softwareOnly = true))
                        } else {
                            holder.release()
                            load = VideoLoad.Failed
                        }
                    }
                })
                load = VideoLoad.Ready(player)
                return player
            }
            holder.keep(start(softwareOnly = false), directory)
        } catch (error: Exception) {
            directory.deleteRecursively()
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "видео не загрузилось: ${Log.describe(error)}")
            load = VideoLoad.Failed
        }
    }
    DisposableEffect(attachment.mediaId) { onDispose { holder.release() } }
    return load
}

private class Holder {
    private var player: Player? = null
    private var directory: File? = null

    fun keep(player: Player, directory: File) {
        release()
        this.player = player
        this.directory = directory
    }

    /** Другой плеер над тем же файлом: папка остаётся. */
    fun swap(next: Player) {
        player?.release()
        player = next
    }

    fun release() {
        player?.release()
        player = null
        directory?.deleteRecursively()
        directory = null
    }
}

/**
 * Аппаратный декодер, который не создался, ExoPlayer меняет на следующий сам (`decoderFallback`).
 * Упавший посреди файла — нет: такой плеер пересобирается только на программных (`softwareOnly`).
 */
// ContentFrame и выбор декодеров Media3 помечены нестабильными: их API ещё меняется между версиями.
@OptIn(UnstableApi::class)
private fun player(context: Context, uri: Uri, softwareOnly: Boolean): Player {
    val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
    if (softwareOnly) {
        renderers.setMediaCodecSelector { mimeType, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling).filter { it.softwareOnly }
        }
    }
    return ExoPlayer.Builder(context, renderers).build().apply {
        setMediaItem(MediaItem.fromUri(uri))
        prepare()
    }
}

private val DECODING_ERRORS = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

internal object VideoFiles {
    fun directory(context: Context) = File(context.cacheDir, DIRECTORY)

    private const val DIRECTORY = "playing"
}
