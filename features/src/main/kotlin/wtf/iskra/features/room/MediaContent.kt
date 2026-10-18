package wtf.iskra.features.room

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import wtf.iskra.core.timeline.Attachment
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.design.Theme
import wtf.iskra.features.support.LocalMediaLoader

/** Вложение, которое лента рисует картинкой, а не словом: фото, стикер, видео, кружочек. */
internal val MessageKind.picture: Attachment?
    get() = when (this) {
        is MessageKind.Image -> attachment
        is MessageKind.Sticker -> attachment
        is MessageKind.Video -> attachment
        is MessageKind.VideoNote -> attachment
        else -> null
    }

/**
 * Картинка сообщения. Место под неё отводится сразу, по пропорциям, которые прислал отправитель, —
 * чтобы лента не прыгала, когда байты придут; до них — тихая плашка, а не спиннер.
 *
 * Стикер — без подложки: это сказанное, а не вложение. Кружочек — круг, и играет на месте, по
 * нажатию (`noteTaps` растёт с каждым), не на весь экран. Фото и видео открываются в `MediaViewer`.
 */
@Composable
internal fun MediaContent(kind: MessageKind, modifier: Modifier = Modifier, messageId: String = "", noteTaps: Int = 0) {
    val attachment = kind.picture ?: return
    if (kind is MessageKind.VideoNote) return VideoNote(messageId, attachment, noteTaps, modifier)
    val size = frame(kind, attachment)
    val shape = when (kind) {
        is MessageKind.VideoNote -> CircleShape
        is MessageKind.Sticker -> RoundedCornerShape(Theme.Radius.control)
        else -> RoundedCornerShape(Theme.Radius.picture)
    }
    val image = rememberThumbnail(attachment, maxOf(size.width, size.height))
    Box(
        modifier
            .size(size)
            .clip(shape)
            .then(if (kind is MessageKind.Sticker || image != null) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                image,
                contentDescription = attachment.caption,
                contentScale = if (kind is MessageKind.Sticker) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (kind is MessageKind.Video || kind is MessageKind.VideoNote) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(Theme.Size.uploadRing).clip(CircleShape).background(Color.Black.copy(alpha = Theme.Backdrop.DIM)),
            )
        }
    }
}

/** Размер места под картинку: ширина пузыря-картинки, высота по пропорции, но не выше потолка. */
internal fun frame(kind: MessageKind, attachment: Attachment): DpSize = when (kind) {
    is MessageKind.Sticker -> DpSize(Theme.Size.sticker, Theme.Size.sticker)
    is MessageKind.VideoNote -> DpSize(Theme.Size.videoNote, Theme.Size.videoNote)
    else -> {
        val ratio = (attachment.aspectRatio ?: DEFAULT_RATIO).toFloat().coerceIn(MIN_RATIO, MAX_RATIO)
        val width = Theme.Size.picture
        val height: Dp = minOf(width / ratio, Theme.Size.pictureMaxHeight)
        DpSize(if (height == Theme.Size.pictureMaxHeight) height * ratio else width, height)
    }
}

private const val DEFAULT_RATIO = 4.0 / 3.0
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 3f

/**
 * Миниатюра из памяти или через `MediaLoader`. Только в памяти: в зашифрованной комнате это открытый
 * текст, и на диск приложения он не пишется — кэш на диске один, зашифрованный стор SDK.
 */
@Composable
internal fun rememberThumbnail(attachment: Attachment, side: Dp): ImageBitmap? {
    val loader = LocalMediaLoader.current
    val pixels = with(LocalDensity.current) { side.roundToPx() }
    val key = "${attachment.mediaId}@$pixels"
    var image by remember(key) { mutableStateOf(Thumbnails.get(key)) }
    LaunchedEffect(key, loader) {
        if (image != null || loader == null) return@LaunchedEffect
        val bytes = runCatching { loader.thumbnail(attachment, pixels) }.getOrNull() ?: return@LaunchedEffect
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return@LaunchedEffect
        Thumbnails.put(key, bitmap)
        image = bitmap
    }
    return image
}

private object Thumbnails {
    private val cache = object : LruCache<String, ImageBitmap>(BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, image: ImageBitmap) {
        cache.put(key, image)
    }

    /** Тридцать два мегабайта картинок — пара экранов фото; дальше — заново у SDK, у него свой кэш. */
    private const val BYTES = 32 * 1024 * 1024
}

/**
 * Кружочек, который играет там же, где лежит. Первое нажатие качает и запускает, следующие —
 * пауза и дальше; доиграл — снова кадр и значок. Динамик один: заговорило другое — кружок умолкает.
 */
// ContentFrame и выбор декодеров Media3 помечены нестабильными: их API ещё меняется между версиями.
@OptIn(UnstableApi::class)
@Composable
private fun VideoNote(messageId: String, attachment: Attachment, taps: Int, modifier: Modifier) {
    var wanted by remember(messageId) { mutableStateOf(false) }
    val load = rememberVideo(attachment, wanted)
    val player = (load as? VideoLoad.Ready)?.player
    var playing by remember(player) { mutableStateOf(false) }
    val image = rememberThumbnail(attachment, Theme.Size.videoNote)

    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        val current = player
        when {
            current == null -> wanted = true
            current.isPlaying -> current.pause()
            else -> {
                VoicePlayback.stop()
                Speaker.claim(messageId)
                current.play()
            }
        }
    }
    // Пришёл файл, о котором просили, — сразу играть: человек нажал ради этого.
    LaunchedEffect(player) {
        if (player == null) return@LaunchedEffect
        VoicePlayback.stop()
        Speaker.claim(messageId)
        player.play()
    }
    // Подпиской, а не значением из композиции: то отстаёт на кадр и остановило бы только что запущенное.
    LaunchedEffect(player) { Speaker.owner.collect { if (it != messageId) player?.pause() } }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player?.pause()
                    player?.seekTo(0)
                    Speaker.release(messageId)
                }
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
            Speaker.release(messageId)
        }
    }

    Box(
        modifier.size(Theme.Size.videoNote).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val still: @Composable () -> Unit = {
            if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (player != null) {
            ContentFrame(player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Crop, shutter = still)
        } else {
            still()
        }
        when {
            load is VideoLoad.Loading -> CircularProgressIndicator(Modifier.size(Theme.Size.uploadRing), color = Color.White)
            load is VideoLoad.Failed -> PlateIcon(Icons.Filled.ErrorOutline)
            !playing -> PlateIcon(Icons.Filled.PlayArrow)
        }
    }
}

@Composable
private fun PlateIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(Theme.Size.uploadRing).clip(CircleShape).background(Color.Black.copy(alpha = Theme.Backdrop.DIM)),
    )
}
