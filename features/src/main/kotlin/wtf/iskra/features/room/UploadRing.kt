package wtf.iskra.features.room

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/** Отменить отправку вложения — `TimelineStore.discard`. Null — нечем (тред, чужая лента). */
internal val LocalDiscardUpload = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * Вложение своего на пути вверх, как его рисует Telegram: кольцо поверх картинки, что заполняется по мере ухода байтов,
 * с крестом посередине, который забирает сообщение назад. Порт `UploadProgressOverlay`.
 *
 * Часы рядом с пузырём говорят то же самое в принципе, а на деле — ничего: минутное видео на медленной сети с теми же часами
 * неотличимо от застрявшего. Крест здесь потому, что к кольцу тянется взгляд, когда передумали: это тот же `discard`, что
 * предлагает красный значок, и до получателя ничего ещё не дошло.
 */
@Composable
internal fun UploadRing(progress: Double, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(maxOf(MINIMUM, progress.toFloat()), tween(Theme.Timing.chrome.inWholeMilliseconds.toInt(), easing = LinearEasing), label = "upload")
    val stroke = Theme.Border.hairline * RING_STROKE
    Box(
        modifier
            .size(Theme.Size.uploadRing)
            .background(Color.Black.copy(alpha = Theme.Backdrop.PLATE), CircleShape)
            .clickable(onClickLabel = stringResource(R.string.message_delivery_cancel_upload), onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(Theme.Size.uploadRing).padding(Theme.Spacing.tight)) {
            val width = stroke.toPx()
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * shown,
                useCenter = false,
                topLeft = Offset(width / 2, width / 2),
                size = Size(size.width - width, size.height - width),
                style = Stroke(width, cap = StrokeCap.Round),
            )
        }
        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(Theme.Size.menuIcon))
    }
}

/** Файл — строка текста со значком, и кольцо величиной с пузырь легло бы на имя; тонкая полоса у основания говорит то же. */
@Composable
internal fun UploadBar(progress: Double, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { progress.toFloat() }, color = LocalAccent.current.control, modifier = modifier.fillMaxWidth())
}

private const val MINIMUM = 0.02f
private const val RING_STROKE = 2.5f
