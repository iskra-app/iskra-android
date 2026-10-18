package wtf.iskra.features.room

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import wtf.iskra.design.Theme
import kotlin.math.abs

/**
 * Потянуть сообщение к середине экрана — ответить на него (`SwipeToReply` на iOS). Едет вся строка:
 * лицо, время и пузырь, а не пузырь из-под своего аватара.
 *
 * Чей это жест, решается **один раз**, по первому движению, и держится, пока палец не поднят:
 * начавшееся вниз — ленты, она листается и забирает касание; начавшееся вбок — строки. Спрашивать
 * заново на каждом событии значило бы, что палец, ушедший вниз и свернувший вбок, листает переписку
 * и тащит из неё сообщение одновременно.
 *
 * Своё сообщение справа и тянется влево, чужое — вправо. Тянется с сопротивлением и всегда
 * возвращается: это ярлык для цитаты, а не то, что улетает с экрана.
 */
@Composable
internal fun SwipeToReply(towardStart: Boolean, enabled: Boolean, onReply: () -> Unit, content: @Composable () -> Unit) {
    if (!enabled) return content()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { Theme.Size.swipeToReply.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(drag, label = "swipe")
    Box {
        Icon(
            Icons.AutoMirrored.Filled.Reply,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(if (towardStart) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = Theme.Spacing.normal)
                .alpha((abs(shown) / threshold).coerceIn(0f, 1f))
                .scale(if (armed) ARMED_SCALE else RESTING_SCALE),
        )
        Box(
            Modifier
                .graphicsLayer { translationX = shown }
                .pointerInput(towardStart) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var travelled = androidx.compose.ui.geometry.Offset.Zero
                        var sideways: Boolean? = null
                        var total = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            val delta = change.positionChange()
                            if (sideways == null) {
                                travelled += delta
                                if (travelled.getDistance() > viewConfiguration.touchSlop) sideways = abs(travelled.x) > abs(travelled.y)
                                if (sideways != true) continue
                                total = travelled.x
                            } else if (sideways == true) {
                                total += delta.x
                            } else {
                                continue
                            }
                            change.consume()
                            val directed = if (towardStart) minOf(0f, total) else maxOf(0f, total)
                            // Поддаётся, потом упирается — никогда не улетает к краю.
                            drag = directed.coerceIn(-threshold * STRETCH, threshold * STRETCH) * GIVE
                            val passed = abs(drag) >= threshold
                            if (passed != armed) {
                                armed = passed
                                if (passed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                        if (armed) onReply()
                        armed = false
                        drag = 0f
                    }
                },
        ) { content() }
    }
}

private const val STRETCH = 1.6f
private const val GIVE = 0.7f
private const val ARMED_SCALE = 1.1f
private const val RESTING_SCALE = 0.9f
