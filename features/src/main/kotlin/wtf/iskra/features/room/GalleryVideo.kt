package wtf.iskra.features.room

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import kotlinx.coroutines.delay
import wtf.iskra.core.timeline.Message
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.max

/**
 * Страница видео в просмотрщике. Качается, только когда впереди, и сразу играет; пролистнули —
 * пауза. Пока файла нет — кадр из ленты во весь экран, а не чёрное. Не увеличивается: щипок по
 * идущему видео — почти всегда промах. Нажатие убирает хром, смахивание вниз закрывает.
 *
 * Плеер отдаётся наверх (`onPlayer`): кнопки — в подвале, который не едет вместе со страницей.
 */
// ContentFrame и выбор декодеров Media3 помечены нестабильными: их API ещё меняется между версиями.
@OptIn(UnstableApi::class)
@Composable
internal fun VideoPage(
    message: Message,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onDrag: (Float) -> Unit,
    onDismiss: () -> Unit,
    onPlayer: (Player?) -> Unit,
) {
    val attachment = message.kind.picture ?: return
    val preview = rememberThumbnail(attachment, frame(message.kind, attachment).let { if (it.width > it.height) it.width else it.height })
    val load = rememberVideo(attachment, wanted = isCurrent)
    val player = (load as? VideoLoad.Ready)?.player

    LaunchedEffect(player, isCurrent) {
        if (player == null) return@LaunchedEffect
        if (isCurrent) {
            Speaker.claim(message.id)
            player.play()
        } else {
            player.pause()
        }
    }
    // Динамик забрал кто-то ещё — видео умолкает. Подпиской, а не значением из композиции: то
    // отстаёт на кадр и поставило бы на паузу только что запущенное.
    LaunchedEffect(player) { Speaker.owner.collect { if (it != message.id) player?.pause() } }
    DisposableEffect(player) {
        onPlayer(player)
        onDispose {
            onPlayer(null)
            Speaker.release(message.id)
        }
    }

    var drag by remember { mutableFloatStateOf(0f) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (abs(drag) > area.height * DISMISS_FRACTION) {
                            onDismiss()
                        } else {
                            drag = 0f
                            onDrag(0f)
                        }
                    },
                    onDragCancel = {
                        drag = 0f
                        onDrag(0f)
                    },
                ) { change, amount ->
                    change.consume()
                    drag += amount
                    onDrag((abs(drag) / max(1, area.height)).coerceIn(0f, 1f))
                }
            }
            .graphicsLayer { translationY = drag },
        contentAlignment = Alignment.Center,
    ) {
        val still: @Composable () -> Unit = {
            if (preview != null) Image(preview, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (player != null) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = still,
            )
        } else {
            still()
            when (load) {
                VideoLoad.Loading -> CircularProgressIndicator(color = Color.White)
                VideoLoad.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = Color.White)
                    Text(stringResource(R.string.message_media_load_failed), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Unit
            }
        }
    }
}

/** Кнопки видео в подвале: пауза, ±15 с, ползунок, сколько прошло и сколько осталось, скорость. */
@Composable
internal fun VideoControls(player: Player) {
    var position by remember(player) { mutableLongStateOf(player.currentPosition) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var seeking by remember(player) { mutableStateOf<Float?>(null) }
    var rate by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }

    // Время идёт само; плеер говорит только о скачках, поэтому опрашивается, пока видно подвал.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            delay(TICK_MS)
        }
    }

    val locale = LocalConfiguration.current.locales[0]
    val number = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    Column(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy)) {
        Slider(
            value = seeking ?: if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { seeking = it },
            onValueChangeFinished = {
                seeking?.let { player.seekTo((it * duration).toLong()) }
                seeking = null
            },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = Theme.Backdrop.PLATE)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(DateUtils.formatElapsedTime(position / MILLIS), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.seekTo((player.currentPosition - SKIP_MS).coerceAtLeast(0)) }) {
                    Icon(Icons.Filled.FastRewind, contentDescription = stringResource(R.string.message_media_skip_back), tint = Color.White)
                }
                IconButton(onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.message_media_pause else R.string.message_media_play),
                        tint = Color.White,
                        modifier = Modifier.size(Theme.Size.voiceButton),
                    )
                }
                IconButton(onClick = { player.seekTo((player.currentPosition + SKIP_MS).coerceAtMost(max(0, duration))) }) {
                    Icon(Icons.Filled.FastForward, contentDescription = stringResource(R.string.message_media_skip_forward), tint = Color.White)
                }
                TextButton(onClick = {
                    val next = RATES[(RATES.indexOf(rate).coerceAtLeast(0) + 1) % RATES.size]
                    player.setPlaybackSpeed(next)
                    rate = next
                }) {
                    Text(stringResource(R.string.video_rate, number.format(rate)), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
            Text(
                stringResource(R.string.video_remaining, DateUtils.formatElapsedTime(max(0, duration - position) / MILLIS)),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/** Скорости по кругу, как в Telegram: обычная, быстрее, ещё быстрее, медленнее. */
private val RATES = listOf(1f, 1.5f, 2f, 0.5f)
private const val SKIP_MS = 15_000L
private const val TICK_MS = 200L
private const val MILLIS = 1000L
private const val DISMISS_FRACTION = 0.2f
