package wtf.iskra.features.room

import android.content.ActivityNotFoundException
import wtf.iskra.features.support.NetworkCost
import wtf.iskra.features.support.ManualDownloads
import wtf.iskra.features.support.LocalAppSettings
import wtf.iskra.features.map.MapSnapshot
import wtf.iskra.design.LocalAccent
import wtf.iskra.core.media.MediaAutoDownload
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Date
import wtf.iskra.features.support.FileOpener
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.LocalMediaLoader
import kotlin.math.max

/** Вложение, которое лента рисует строкой в пузыре: значок, имя, подробность. */
internal val MessageKind.isAttachmentRow: Boolean
    get() = this is MessageKind.File || this is MessageKind.Location || this is MessageKind.Voice || this is MessageKind.Audio

/**
 * Файл, место, голосовое, музыка — строкой в пузыре. Кружок слева — то, что нажимают: у голосового
 * он играет, у места — открывает карту, у файла — отдаёт его приложению, которое выберет человек
 * (`FileOpener`).
 */
@Composable
internal fun AttachmentContent(message: Message, ink: Color, fill: Color, onFill: Color) {
    when (val kind = message.kind) {
        is MessageKind.Voice -> Playable(message, kind.attachment, kind.waveform, ink, fill, onFill)
        is MessageKind.Audio -> Playable(message, kind.attachment, null, ink, fill, onFill)
        is MessageKind.File -> {
            val context = LocalContext.current
            val loader = LocalMediaLoader.current
            val scope = rememberCoroutineScope()
            val opening by FileOpener.opening.collectAsStateWithLifecycle()
            var failed by remember(message.id) { mutableStateOf(false) }
            Line(
                icon = if (failed) Icons.Filled.ErrorOutline else Icons.AutoMirrored.Filled.InsertDriveFile,
                title = kind.attachment.filename,
                detail = kind.attachment.size?.let { Formatter.formatShortFileSize(context, it) },
                ink = ink,
                fill = fill,
                onFill = onFill,
                busy = opening == kind.attachment.mediaId,
                clickLabel = stringResource(R.string.message_media_open_file),
                onClick = loader?.let { mediaLoader ->
                    { scope.launch { failed = !FileOpener.open(context, kind.attachment, mediaLoader) } }
                },
            )
        }
        is MessageKind.Location -> {
            val context = LocalContext.current
            val live = kind.live
            // Трансляция заканчивается сама: «до 18:30» должно смениться на «закончилась», не дожидаясь
            // перерисовки от нового события.
            val now by produceState(Instant.now(), live) {
                while (live != null) {
                    delay(LIVE_TICK_MS)
                    value = Instant.now()
                }
            }
            val waiting = live != null && !live.hasPosition
            val openInMaps: () -> Unit = {
                // Карту выбирает человек: координаты уходят только в приложение, которое он открыл.
                val label = Uri.encode(kind.name.orEmpty())
                val uri = Uri.parse("$GEO${kind.latitude},${kind.longitude}?q=${kind.latitude},${kind.longitude}($label)")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    // Карт на телефоне нет — нажатию просто нечего открыть.
                }
            }
            // Карта в пузыре: снимок (`MapSnapshotter`), а не живая карта на каждое сообщение. Грузится, как фото, по правилам автозагрузки
            // — тайлы видят район, который смотрят, — иначе координаты и «Показать карту». Без первой точки показывать нечего.
            if (!waiting) {
                val setting by (LocalAppSettings.current?.autoDownload ?: remember { MutableStateFlow(MediaAutoDownload.DEFAULT) }).collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { NetworkCost.watch(context) }
                val expensive by NetworkCost.isExpensive.collectAsStateWithLifecycle()
                val key = "geo:${kind.latitude},${kind.longitude}"
                val shown = setting.allows(expensive) || ManualDownloads.contains(key)
                val shape = RoundedCornerShape(Theme.Radius.picture)
                Box(
                    Modifier
                        .size(Theme.Size.picture, Theme.Size.map)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClickLabel = stringResource(R.string.location_open_in_maps), onClick = openInMaps),
                    contentAlignment = Alignment.Center,
                ) {
                    if (shown) {
                        MapSnapshot(kind.latitude, kind.longitude, Theme.Size.picture, Theme.Size.map, stringResource(R.string.location_title), Modifier.fillMaxSize())
                        Icon(
                            Icons.Filled.Place,
                            contentDescription = null,
                            tint = if (live?.isActive(now) == true) LocalAccent.current.control else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(Theme.Size.menuIcon).offset(y = -Theme.Size.menuIcon / 2),
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
                            Text(stringResource(R.string.location_coordinates, kind.latitude, kind.longitude), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { ManualDownloads.ask(key) }) { Text(stringResource(R.string.location_show_map)) }
                        }
                    }
                }
            }
            Line(
                icon = Icons.Filled.Place,
                title = kind.name ?: stringResource(R.string.location_title),
                detail = when {
                    live == null -> stringResource(R.string.location_coordinates, kind.latitude, kind.longitude)
                    waiting -> stringResource(R.string.location_live_waiting)
                    live.isActive(now) -> stringResource(
                        R.string.location_live_until,
                        DateFormat.getTimeFormat(context).format(Date.from(live.until)),
                    )
                    else -> stringResource(R.string.location_live_ended)
                },
                ink = ink,
                fill = fill,
                onFill = onFill,
                // Без первой точки открывать нечего: карта встала бы на 0°, 0°.
                onClick = openInMaps.takeUnless { waiting },
            )
        }
        else -> Unit
    }
}

@Composable
private fun Line(
    icon: ImageVector,
    title: String,
    detail: String?,
    ink: Color,
    fill: Color,
    onFill: Color,
    busy: Boolean = false,
    clickLabel: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.then(if (onClick != null) Modifier.clickable(enabled = !busy, onClickLabel = clickLabel, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        Circle(fill) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(Theme.Size.voiceButton), color = onFill, strokeWidth = Theme.Size.waveformBar)
            } else {
                Icon(icon, contentDescription = null, tint = onFill)
            }
        }
        Column(Modifier.width(Theme.Size.audioTrack)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = QUIET))
        }
    }
}

@Composable
private fun Circle(fill: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.size(Theme.Size.minimumTapTarget).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Голосовое или музыка: кнопка, волна (у музыки — имя), время. */
@Composable
private fun Playable(
    message: Message,
    attachment: wtf.iskra.core.timeline.Attachment,
    waveform: List<Float>?,
    ink: Color,
    fill: Color,
    onFill: Color,
) {
    val loader = LocalMediaLoader.current
    val playback by VoicePlayback.state.collectAsStateWithLifecycle()
    val mine = playback.messageId == message.id
    val progress = if (mine) playback.progress else 0f
    val play = stringResource(R.string.message_media_play)
    val pause = stringResource(R.string.message_media_pause)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal)) {
        Circle(
            fill,
            Modifier.clickable(
                enabled = loader != null,
                onClickLabel = if (mine && playback.isPlaying) pause else play,
            ) { loader?.let { VoicePlayback.toggle(message.id, attachment, it) } },
        ) {
            when {
                mine && playback.isLoading -> CircularProgressIndicator(Modifier.size(Theme.Size.voiceButton), color = onFill, strokeWidth = Theme.Size.waveformBar)
                mine && playback.failed -> Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = onFill)
                mine && playback.isPlaying -> Icon(Icons.Filled.Pause, contentDescription = null, tint = onFill)
                waveform == null && !mine -> Icon(Icons.Filled.MusicNote, contentDescription = null, tint = onFill)
                else -> Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onFill)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
            if (waveform != null) {
                Waveform(waveform, progress, played = if (fill == ink) ink else fill, rest = ink.copy(alpha = REST))
            } else {
                Text(
                    attachment.caption ?: attachment.filename,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(Theme.Size.audioTrack),
                )
            }
            attachment.durationMs?.let { duration ->
                Text(DateUtils.formatElapsedTime(duration / MILLIS), style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = QUIET))
            }
        }
    }
}

/**
 * Волна голосового: столбики по отсчётам отправителя, залитые до того места, где сейчас голос.
 * Отсчётов больше, чем столбиков, — берётся самый громкий из каждой пачки, чтобы слоги не терялись.
 */
@Composable
private fun Waveform(samples: List<Float>, progress: Float, played: Color, rest: Color) {
    Canvas(Modifier.width(Theme.Size.audioTrack).height(Theme.Size.waveform)) {
        val bar = Theme.Size.waveformBar.toPx()
        val gap = bar
        val count = max(1, ((size.width + gap) / (bar + gap)).toInt())
        val minimum = Theme.Size.waveformBarMinimum.toPx()
        val levels = List(count) { index ->
            if (samples.isEmpty()) return@List 0f
            val from = index * samples.size / count
            val to = max(from + 1, (index + 1) * samples.size / count).coerceAtMost(samples.size)
            samples.subList(from, to).maxOrNull() ?: 0f
        }
        levels.forEachIndexed { index, level ->
            val height = max(minimum, level.coerceIn(0f, 1f) * size.height)
            val x = index * (bar + gap)
            drawRoundRect(
                color = if ((index + 0.5f) / count <= progress) played else rest,
                topLeft = Offset(x, (size.height - height) / 2f),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2f),
            )
        }
    }
}

private const val GEO = "geo:"
private const val LIVE_TICK_MS = 30_000L
private const val QUIET = 0.7f
private const val REST = 0.35f
private const val MILLIS = 1000L
