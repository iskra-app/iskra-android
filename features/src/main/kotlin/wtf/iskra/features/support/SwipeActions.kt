package wtf.iskra.features.support

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import wtf.iskra.design.Theme

/** Кнопка под строкой: слово, значок, цвет и что сделать. */
class SwipeAction(val title: String, val icon: ImageVector, val color: Color, val onColor: Color, val run: () -> Unit)

/**
 * Строка, которую смахивают, — как в «Почте» и «Сообщениях» (`swipeActions` на iOS). Смахнутая
 * чуть — открывает кнопки своей стороны и стоит открытой; смахнутая решительно, за половину ширины,
 * делает первое действие стороны и возвращается. Без жеста те же действия — в меню доступности.
 *
 * Тянется только горизонтальным жестом: вертикальный остаётся списку, он листается.
 */
@Composable
fun SwipeActions(
    leading: List<SwipeAction>,
    trailing: List<SwipeAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var width by remember { mutableIntStateOf(0) }
    val button = with(LocalDensity.current) { Theme.Size.swipeAction.toPx() }
    val leadingWidth = button * leading.size
    val trailingWidth = button * trailing.size

    fun settle(velocity: Float) {
        val value = offset.value
        val full = width * FULL_SWIPE
        scope.launch {
            when {
                value > full && leading.isNotEmpty() -> { offset.animateTo(0f); leading.first().run() }
                value < -full && trailing.isNotEmpty() -> { offset.animateTo(0f); trailing.first().run() }
                value > leadingWidth / 2 || (value > 0 && velocity > FLING) -> offset.animateTo(leadingWidth)
                value < -trailingWidth / 2 || (value < 0 && velocity < -FLING) -> offset.animateTo(-trailingWidth)
                else -> offset.animateTo(0f)
            }
        }
    }

    fun close() {
        scope.launch { offset.animateTo(0f) }
    }

    Box(
        modifier
            .onSizeChanged { width = it.width }
            .semantics {
                customActions = (leading + trailing).map { action -> CustomAccessibilityAction(action.title) { action.run(); true } }
            },
    ) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxHeight()) { leading.forEach { Button(it) { close(); it.run() } } }
            Row(Modifier.fillMaxHeight()) { trailing.reversed().forEach { Button(it) { close(); it.run() } } }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val limit = width.toFloat()
                        val next = (offset.value + delta).coerceIn(if (trailing.isEmpty()) 0f else -limit, if (leading.isEmpty()) 0f else limit)
                        scope.launch { offset.snapTo(next) }
                    },
                    onDragStopped = { velocity -> settle(velocity) },
                ),
        ) { content() }
    }
}

@Composable
private fun Button(action: SwipeAction, onClick: () -> Unit) {
    Column(
        Modifier
            .width(Theme.Size.swipeAction)
            .fillMaxHeight()
            .background(action.color)
            .clickable(onClick = onClick)
            .padding(Theme.Spacing.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(action.icon, contentDescription = null, tint = action.onColor)
        Text(action.title, color = action.onColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** За половину ширины — уже не «открыть кнопки», а «сделать». */
private const val FULL_SWIPE = 0.5f

/** Пикселей в секунду, после которых бросок открывает кнопки и с короткого пути. */
private const val FLING = 1200f

