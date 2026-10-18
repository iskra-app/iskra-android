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
 * Кнопка голосового: зажал — пошла запись, отпустил — ушло, увёл палец влево — отменено
 * (docs/PROMPT.md, 11.3). Разрешения нет — первое нажатие его просит, а не пишет.
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
) {
    val accent = LocalAccent.current
    val cancelDistance = with(LocalDensity.current) { Theme.Size.swipeToReply.toPx() * CANCEL_STRETCH }
    val label = stringResource(R.string.voice_record)
    Box(
        modifier
            .size(Theme.Size.minimumTapTarget)
            .clip(CircleShape)
            .background(if (recording) accent.control else MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (!hasPermission()) {
                        onNeedPermission()
                        return@awaitEachGesture
                    }
                    if (!onStart()) return@awaitEachGesture
                    var cancelled = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        change.consume()
                        if (change.position.x - down.position.x < -cancelDistance) {
                            cancelled = true
                            onCancel()
                            break
                        }
                        if (!change.pressed) break
                    }
                    if (!cancelled) onFinish()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = null, tint = if (recording) accent.foreground else accent.control)
    }
}

/** Вместо поля ввода, пока идёт запись: красная точка, сколько записано и куда вести палец, чтобы отменить. */
@Composable
internal fun RecordingStrip(elapsedMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier.height(Theme.Size.minimumTapTarget).padding(horizontal = Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        Box(Modifier.size(Theme.Size.recordingDot).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Text(DateUtils.formatElapsedTime(elapsedMs / MILLIS), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.recording_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Насколько дальше порога ответа смахиванием надо увести палец, чтобы отменить запись. */
private const val CANCEL_STRETCH = 2f
private const val MILLIS = 1000L
