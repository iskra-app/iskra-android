package wtf.iskra.features.room

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import kotlin.math.max
import kotlin.math.roundToInt

/** Одно действие в колонке меню: слово, значок и опасное ли оно (красным). */
internal class MenuAction(val title: String, val icon: ImageVector, val destructive: Boolean = false, val run: () -> Unit)

/** Пять, что предлагаются первыми (`QuickReaction`): то, что люди правда ставят, за одно нажатие. */
internal val QUICK_REACTIONS = listOf("❤️", "👍", "😂", "😮", "😢")

/**
 * Меню долгого нажатия — своё, а не системное (`MessageMenuOverlay` на iOS). Три вещи над затемнённой
 * перепиской: полоса эмодзи, само сообщение, поднятое со страницы, и колонка действий. Середина —
 * главное: меню, закрывающее сообщение, о котором оно, заставляет отвечать «а какое это было?» по памяти.
 *
 * Сообщение рисуется второй раз (`lifted`) ровно там, где стояло в ленте, и сдвигается, только если
 * иначе полоса или действия не влезут на экран. «+» меняет действия на все эмодзи — недавние сверху.
 */
@Composable
internal fun MessageMenuOverlay(
    anchor: Rect,
    alignEnd: Boolean,
    mine: Set<String>,
    canReact: Boolean,
    actions: List<MenuAction>,
    recent: List<String>,
    onReact: (String) -> Unit,
    onDismiss: () -> Unit,
    lifted: @Composable () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val closeLabel = stringResource(R.string.message_menu_close)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = Theme.Backdrop.MENU_SCRIM))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = closeLabel, onClick = onDismiss),
        ) {
            if (picking) {
                EmojiPanel(
                    recent = recent,
                    mine = mine,
                    onPick = { onReact(it); onDismiss() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                MenuLayout(anchor = anchor, alignEnd = alignEnd) {
                    if (canReact) {
                        ReactionBar(mine = mine, onPick = { onReact(it); onDismiss() }, onMore = { picking = true })
                    } else {
                        Box(Modifier)
                    }
                    Box(Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) { lifted() }
                    Actions(actions, onDismiss)
                }
            }
        }
    }
}

/**
 * Раскладка трёх частей: сообщение — на своём месте, если влезает; полоса над ним, действия под ним.
 * Не влезает — всё вместе сдвигается в пределах экрана без вырезов и полосы жестов.
 */
@Composable
private fun MenuLayout(anchor: Rect, alignEnd: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val top = WindowInsets.safeDrawing.getTop(density)
    val bottom = WindowInsets.safeDrawing.getBottom(density)
    val gap = with(density) { Theme.Spacing.close.roundToPx() }
    val side = with(density) { Theme.Spacing.close.roundToPx() }
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val loose = Constraints(maxWidth = constraints.maxWidth - 2 * side, maxHeight = constraints.maxHeight)
        val bar = measurables[0].measure(loose)
        val actions = measurables[2].measure(loose)
        val room = constraints.maxHeight - top - bottom - bar.height - actions.height - 4 * gap
        val message = measurables[1].measure(loose.copy(maxHeight = max(0, room)))

        val minY = top + bar.height + gap
        val maxY = constraints.maxHeight - bottom - actions.height - gap - message.height
        val messageY = if (maxY < minY) minY else anchor.top.roundToInt().coerceIn(minY, maxY)
        fun x(width: Int, edge: Float): Int {
            val wanted = if (alignEnd) edge.roundToInt() - width else edge.roundToInt()
            return wanted.coerceIn(side, max(side, constraints.maxWidth - side - width))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            message.place(x(message.width, if (alignEnd) anchor.right else anchor.left), messageY)
            bar.place(x(bar.width, if (alignEnd) anchor.right else anchor.left), messageY - gap - bar.height)
            actions.place(x(actions.width, if (alignEnd) anchor.right else anchor.left), messageY + message.height + gap)
        }
    }
}

/** Пять эмодзи и «+». Свои отмечены подложкой — нажатие на свою снимает её. */
@Composable
private fun ReactionBar(mine: Set<String>, onPick: (String) -> Unit, onMore: () -> Unit) {
    val accent = LocalAccent.current
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = Theme.Spacing.close) {
        Row(Modifier.height(Theme.Size.reactionBar).padding(horizontal = Theme.Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
            QUICK_REACTIONS.forEach { emoji -> EmojiCell(emoji, emoji in mine, accent.control, onPick) }
            Box(
                Modifier.size(Theme.Size.reactionCell).clip(CircleShape).clickable(role = Role.Button, onClick = onMore),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.message_menu_more_reactions))
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, chosen: Boolean, accent: Color, onPick: (String) -> Unit) {
    val label = stringResource(if (chosen) R.string.message_menu_remove_quick else R.string.message_menu_add_quick, emoji)
    Box(
        Modifier
            .size(Theme.Size.reactionCell)
            .clip(CircleShape)
            .background(if (chosen) accent.copy(alpha = Theme.Opacity.SELECTED_TAB) else Color.Transparent)
            .clickable(role = Role.Button) { onPick(emoji) }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Actions(actions: List<MenuAction>, onDismiss: () -> Unit) {
    if (actions.isEmpty()) {
        Box(Modifier)
        return
    }
    Surface(
        shape = RoundedCornerShape(Theme.Radius.card),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = Theme.Spacing.close,
        modifier = Modifier.width(Theme.Size.menuWidth),
    ) {
        Column {
            actions.forEachIndexed { index, action ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val ink = if (action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(Theme.Size.menuRow)
                        .clickable(role = Role.Button) {
                            onDismiss()
                            action.run()
                        }
                        .padding(horizontal = Theme.Spacing.roomy),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(action.title, color = ink, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(action.icon, contentDescription = null, tint = ink, modifier = Modifier.size(Theme.Size.menuIcon))
                }
            }
        }
    }
}

/** Все эмодзи, недавние сверху, с вкладками групп — как клавиатура (`EmojiPanel`). */
@Composable
internal fun EmojiPanel(recent: List<String>, mine: Set<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val sections = remember(recent) {
        buildList {
            if (recent.isNotEmpty()) add(Section(R.string.reactions_recent, Icons.Outlined.Schedule, recent))
            EmojiCatalog.categories.forEach { add(Section(it.title, tabIcon(it.id), it.emoji)) }
        }
    }
    // Где в сетке начинается каждая группа — чтобы вкладка вела туда.
    val starts = remember(sections) {
        var index = 0
        sections.map { section -> index.also { index += section.emoji.size + 1 } }
    }
    Surface(
        shape = RoundedCornerShape(topStart = Theme.Radius.menu, topEnd = Theme.Radius.menu),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(Modifier.padding(bottom = with(LocalDensity.current) { WindowInsets.safeDrawing.getBottom(this).toDp() })) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                sections.forEachIndexed { index, section ->
                    val label = stringResource(section.title)
                    Box(
                        Modifier
                            .size(Theme.Size.reactionCell)
                            .clip(CircleShape)
                            .clickable(role = Role.Tab) { scope.launch { grid.scrollToItem(starts[index]) } }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        // Значок, а не эмодзи группы: смайлик вместо «смайлики» не отличить от того, что шлют.
                        Icon(section.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(Theme.Size.reactionCell),
                modifier = Modifier.fillMaxWidth().height(Theme.Size.emojiPanel * PANEL_SCALE),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Theme.Spacing.close),
            ) {
                sections.forEach { (title, _, emoji) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(Theme.Size.emojiHeader).padding(top = Theme.Spacing.close),
                        )
                    }
                    items(emoji) { item -> EmojiCell(item, item in mine, accent.control, onPick) }
                }
            }
        }
    }
}

private data class Section(val title: Int, val icon: ImageVector, val emoji: List<String>)

private fun tabIcon(category: String): ImageVector = when (category) {
    "smileys" -> Icons.Outlined.EmojiEmotions
    "animals" -> Icons.Outlined.Pets
    "food" -> Icons.Outlined.Restaurant
    "activity" -> Icons.Outlined.SportsSoccer
    "places" -> Icons.Outlined.DirectionsCar
    "objects" -> Icons.Outlined.Lightbulb
    "symbols" -> Icons.Outlined.MusicNote
    else -> Icons.Outlined.Flag
}

/** Панель на телефоне выше, чем на iOS-листе: полтора её роста — две трети экрана не закрывают. */
private const val PANEL_SCALE = 1.5f
