package wtf.iskra.features.room

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Кнопка голосового: зажал — пошла запись, отпустил — ушло, увёл палец влево — отменено, вверх до
 * замка — пишется дальше без пальца (docs/PROMPT.md, 11.3; `RecordLock` на iOS). Разрешения нет —
 * первое нажатие его просит, а не пишет. [onReach] — сколько пройдено до замка, 0…1, и −1, когда
 * рисовать нечего: по нему рисуется [RecordLock].
 */
@Composable
internal fun MicButton(
    recording: Boolean,
    hasPermission: () -> Boolean,
    onNeedPermission: () -> Unit,
    onStart: () -> Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onLock: () -> Unit = {},
    onReach: (Float) -> Unit = {},
) {
    val accent = LocalAccent.current
    val cancelDistance = with(LocalDensity.current) { Theme.Size.swipeToReply.toPx() * CANCEL_STRETCH }
    val lockDistance = with(LocalDensity.current) { Theme.Size.gestureReach.toPx() }
    val label = stringResource(R.string.voice_record)
    Box(
        modifier
            .size(Theme.Size.minimumTapTarget)
            .clip(CircleShape)
            .background(if (recording) accent.control else MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!hasPermission()) {
                        onNeedPermission()
                        return@awaitEachGesture
                    }
                    if (!onStart()) return@awaitEachGesture
                    onReach(0f)
                    var ended = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        change.consume()
                        val left = down.position.x - change.position.x
                        val up = down.position.y - change.position.y
                        if (left > cancelDistance) {
                            ended = true
                            onReach(-1f)
                            onCancel()
                            break
                        }
                        if (up >= lockDistance) {
                            // Поймал: запись идёт дальше без пальца, отпускание её не отправит.
                            ended = true
                            onLock()
                            break
                        }
                        // Пилюля наполняется, только когда палец идёт вверх, а не вбок к отмене.
                        onReach(if (up > left) (up / lockDistance).coerceIn(0f, 1f) else 0f)
                        if (!change.pressed) break
                    }
                    if (!ended) {
                        onReach(-1f)
                        onFinish()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = if (recording) accent.foreground else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * Куда вести палец, чтобы писать без него (`RecordLock` на iOS): пилюля над кнопкой, акцент поднимается
 * по ней вместе с пальцем, наверху замок закрывается. Без неё жест вверх ничего не обещал: узнать,
 * поймался ли замок, можно было, только отпустив кнопку, — а отпустить и значит отправить.
 */
@Composable
internal fun RecordLock(reach: Float, locked: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val filled = if (locked) 1f else reach.coerceIn(0f, 1f)
    Box(
        modifier
            .size(Theme.Size.recordLock)
            .clip(RoundedCornerShape(Theme.Size.recordLock.width / 2))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(filled).background(accent.control))
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val tint = if (filled > HALF) accent.foreground else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen, contentDescription = null, tint = tint, modifier = Modifier.size(Theme.Size.audioScrubber))
            if (!locked) Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = tint, modifier = Modifier.size(Theme.Size.audioScrubber))
        }
    }
}

/**
 * Вместо поля ввода, пока идёт запись: красная точка, сколько записано и куда вести палец, чтобы
 * отменить. Без пальца ([onCancel] не null) — «Отменить» кнопкой: вести уже нечего.
 */
@Composable
internal fun RecordingStrip(elapsedMs: Long, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    Row(
        modifier.height(Theme.Size.minimumTapTarget).padding(horizontal = Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        Box(Modifier.size(Theme.Size.recordingDot).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Text(DateUtils.formatElapsedTime(elapsedMs / MILLIS), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.recording_cancel), color = MaterialTheme.colorScheme.error) }
        } else {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.recording_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Насколько дальше порога ответа смахиванием надо увести палец, чтобы отменить запись. */
private const val CANCEL_STRETCH = 2f
private const val MILLIS = 1000L
private const val HALF = 0.5f
