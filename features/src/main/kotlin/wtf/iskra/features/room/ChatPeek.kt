package wtf.iskra.features.room

import androidx.compose.foundation.background
import wtf.iskra.features.support.closesOnEscape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.TimelineRow
import wtf.iskra.core.timeline.TimelineStore
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar
import wtf.iskra.features.support.LocalOwnUserId

/** Одно действие под карточкой: слово, значок, что сделать. */
internal class PeekAction(val title: Int, val icon: ImageVector, val run: () -> Unit)

/**
 * Заглянуть в чат, не открывая его: долгое нажатие на строку поднимает живую карточку с последними сообщениями, под ней — действия
 * (открыть, прочитано, закрепить, в архив, в папку). Заглядывание ничего не читает: непрочитанное остаётся непрочитанным, пока чат
 * не откроют (словами об этом карточка не напоминает). Порт `ChatPeek`.
 *
 * Лента — своя на карточку: стор запускается, когда карточка появилась, и закрывается вместе с ней. Сообщения в ней не нажимаются:
 * меню над ними не поднимается — смотрят, а не отвечают.
 */
@Composable
internal fun ChatPeek(
    room: RoomSummary,
    store: TimelineStore,
    ownUserId: String,
    actions: List<PeekAction>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .closesOnEscape(onDismiss)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .safeDrawingPadding()
                .padding(Theme.Spacing.roomy),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = Theme.Size.peekWidth).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
            ) {
                Card(room, store, ownUserId, onOpen, Modifier.weight(1f, fill = false))
                Surface(
                    shape = RoundedCornerShape(Theme.Radius.card),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = Theme.Spacing.close,
                    modifier = Modifier.widthIn(max = Theme.Size.menuWidth),
                ) {
                    Column {
                        actions.forEachIndexed { index, action ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().height(Theme.Size.menuRow)
                                    .clickable(role = Role.Button) { onDismiss(); action.run() }
                                    .padding(horizontal = Theme.Spacing.roomy),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(action.title), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(action.icon, contentDescription = null, modifier = Modifier.size(Theme.Size.menuIcon))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(room: RoomSummary, store: TimelineStore, ownUserId: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val state by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(store) { runCatching { store.start() } }
    DisposableEffect(store) { onDispose { store.close() } }
    Surface(
        shape = RoundedCornerShape(Theme.Radius.menu),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = Theme.Spacing.close,
        modifier = modifier.fillMaxWidth().heightIn(max = Theme.Size.peekHeight),
    ) {
        Column {
            // Заголовок — кнопка «открыть».
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.normal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
            ) {
                Avatar(room.name, room.avatarUrl, Theme.Size.avatarToolbar, seed = room.avatarSeed)
                Column(Modifier.weight(1f)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val messages = remember(state.rows) { state.rows.mapNotNull { (it as? TimelineRow.MessageRow)?.message } }
            val newestFirst = remember(messages) { messages.asReversed() }
            val list = rememberLazyListState()
            // Дальше в историю, когда читающий у самого старого: карточка короче своей истории просит сразу, и этим она заполняется.
            val nearOldest by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= newestFirst.size - 3 } ?: true } }
            LaunchedEffect(nearOldest, state.hasLoaded, state.canLoadMore, newestFirst.size) {
                if (state.hasLoaded && nearOldest && state.canLoadMore && !state.didFailToPaginate) store.loadMore()
            }
            CompositionLocalProvider(LocalOwnUserId provides ownUserId) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close)) {
                        items(newestFirst, key = { it.id }) { message ->
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = Theme.Spacing.tight),
                                horizontalAlignment = if (message.isOwn) Alignment.End else Alignment.Start,
                            ) {
                                if (!message.isOwn && !room.isDirectMessage) {
                                    Text(message.senderName, style = MaterialTheme.typography.labelSmall, color = LocalAccent.current.control, modifier = Modifier.padding(horizontal = Theme.Spacing.close))
                                }
                                BubbleBody(message, interactive = false, revealed = false, noteTaps = 0)
                            }
                        }
                    }
                    if (!state.hasLoaded && messages.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LocalAccent.current.control)
                        }
                    }
                }
            }
        }
    }
}

private const val SCRIM = 0.5f
