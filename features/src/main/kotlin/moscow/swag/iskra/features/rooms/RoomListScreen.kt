package moscow.swag.iskra.features.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import moscow.swag.iskra.design.EntryField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import moscow.swag.iskra.core.rooms.RoomListStore
import moscow.swag.iskra.core.rooms.RoomSummary
import moscow.swag.iskra.core.rooms.UnreadEmphasis
import moscow.swag.iskra.design.LocalAccent
import moscow.swag.iskra.design.PrimaryActionButton
import moscow.swag.iskra.design.SecondaryActionButton
import moscow.swag.iskra.design.Theme
import moscow.swag.iskra.features.R
import moscow.swag.iskra.features.support.AvatarShape
import moscow.swag.iskra.features.support.RoomAvatar
import java.time.Instant
import java.time.ZoneId

/**
 * Список чатов (`RoomListView` на iOS, docs/PROMPT.md, 12.2). Пока — основа этапа 3: заголовок со
 * статусом, строки с последним сообщением, лица, непрочитанное, приглашения. Свайпы, папки, поиск
 * и подглядывание — следующие пункты.
 *
 * Синхронизацию запускает не этот экран: [RoomListStore.start] зовёт корень, явно, когда сеанс
 * открылся (docs/PROMPT.md, 4.2: не побочным эффектом composable).
 */
@Composable
fun RoomListScreen(
    store: RoomListStore,
    onOpen: (roomId: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var showingArchive by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<RoomSummary?>(null) }
    BackHandler(enabled = showingArchive) { showingArchive = false }

    // Поиск — по уже собранным строкам: имя чата, без похода на сервер. Поиск по сообщениям — свой пункт.
    val source = if (showingArchive) state.archivedRooms else state.rooms
    val shown = remember(source, query) {
        val needle = query.trim()
        if (needle.isEmpty()) source else source.filter { it.name.contains(needle, ignoreCase = true) }
    }
    val actions = RoomActions(
        onRead = { scope.launch { store.markRead(it.id) } },
        onUnread = { scope.launch { store.markUnread(it.id) } },
        onPin = { scope.launch { store.setPinned(it.id, !it.isPinned) } },
        onArchive = { scope.launch { store.setArchived(it.id, !it.isArchived) } },
        onLeave = { leaving = it },
    )

    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (showingArchive) {
            ArchiveHeader(onBack = { showingArchive = false })
        } else {
            Header(
                status = when {
                    state.isUnreachable -> R.string.room_list_waiting_for_network
                    state.isUpdating -> R.string.room_list_updating
                    else -> null
                },
                onSettings = onSettings,
            )
        }
        if (!state.isLoadingInitialRooms && source.isNotEmpty()) {
            EntryField(
                value = query,
                onValueChange = { query = it },
                // «Поиск», а не «по сообщениям»: пока ищутся названия чатов; сообщения — с поиском SDK.
                placeholder = stringResource(R.string.profile_search),
                modifier = Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight),
            )
        }

        when {
            state.isLoadingInitialRooms -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current.control)
            }
            showingArchive && source.isEmpty() -> Empty(R.string.room_list_archive_empty_title, R.string.room_list_archive_empty_message)
            source.isEmpty() && state.archivedRooms.isEmpty() -> Empty(R.string.room_list_empty_title, R.string.room_list_empty_message)
            else -> {
                val now = Instant.now()
                LazyColumn(Modifier.fillMaxSize()) {
                    // Архив — строкой над списком, как в Telegram: смахнутые чаты никуда не делись.
                    if (!showingArchive && query.isBlank() && state.archivedRooms.isNotEmpty()) {
                        item(key = "archive") {
                            ArchiveRow(state.archivedRooms.size, state.archivedRooms.any { it.hasUnread }) { showingArchive = true }
                        }
                    }
                    items(shown, key = { it.id }) { room ->
                        if (room.isInvitation) {
                            InviteRow(
                                room = room,
                                onAccept = { scope.launch { store.accept(room.id) } },
                                onDecline = { scope.launch { store.decline(room.id) } },
                            )
                        } else {
                            RoomRow(room = room, now = now, actions = actions, onOpen = { onOpen(room.id) })
                        }
                    }
                }
            }
        }
    }

    leaving?.let { room ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(stringResource(R.string.room_list_swipe_leave_confirm_title)) },
            text = { Text(stringResource(R.string.room_list_swipe_leave_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = null
                    scope.launch { store.leave(room.id) }
                }) { Text(stringResource(R.string.room_list_swipe_leave_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text(stringResource(R.string.room_list_swipe_cancel)) } },
        )
    }

    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = store::clearFailure,
            confirmButton = {
                TextButton(onClick = store::clearFailure) { Text(stringResource(android.R.string.ok)) }
            },
            text = { Text(stringResource(failure.message)) },
        )
    }
}

@Composable
private fun Header(status: Int?, onSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.room_list_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (status != null) {
                Text(
                    stringResource(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title), tint = LocalAccent.current.control)
        }
    }
}

@Composable
private fun Empty(title: Int, message: Int) {
    Box(Modifier.fillMaxSize().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RoomRow(room: RoomSummary, now: Instant, actions: RoomActions, onOpen: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var menu by remember { mutableStateOf(false) }
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onClickLabel = room.name, onLongClick = { menu = true })
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(room)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                room.lastActivity?.let { at ->
                    Text(
                        RoomTime.label(at, now, ZoneId.systemDefault(), locale),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (room.hasUnread) {
                            LocalAccent.current.control
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Preview(room, Modifier.weight(1f))
                Spacer(Modifier.width(Theme.Spacing.close))
                Badge(room)
            }
        }
    }
    RoomMenu(room, menu, onDismiss = { menu = false }, actions)
    }
}

/**
 * Две строки под именем, **зарезервированные** всегда: список, где строки прыгают в высоту от
 * длины последнего сообщения, читается как дёргающийся. В группе первая строка — кто написал.
 */
@Composable
private fun Preview(room: RoomSummary, modifier: Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    val draft = room.draft
    when {
        draft != null -> Text(
            stringResource(R.string.room_list_draft, draft),
            modifier = modifier,
            style = style,
            fontStyle = FontStyle.Italic,
            color = secondary,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        room.lastSender != null && !room.isDirectMessage -> Column(modifier) {
            Text(room.lastSender.orEmpty(), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(room.lastMessage.orEmpty(), style = style, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        else -> Text(
            room.lastMessage.orEmpty(),
            modifier = modifier,
            style = style,
            color = secondary,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Значок непрочитанного: число, или точка, когда считать нечего (беззвучный, помеченный вручную). */
@Composable
private fun Badge(room: RoomSummary) {
    if (!room.hasUnread && !room.isMentioned) return
    val accent = LocalAccent.current
    val fill = when (room.unreadEmphasis) {
        UnreadEmphasis.ORDINARY -> accent.control
        UnreadEmphasis.MUTED -> MaterialTheme.colorScheme.outline
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (room.isMentioned) {
            Box(
                Modifier.size(Theme.Size.unreadBadge).clip(CircleShape).background(fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.room_list_mention_mark), style = MaterialTheme.typography.labelMedium, color = accent.foreground)
            }
        }
        if (room.hasUnreadCount) {
            Box(
                Modifier
                    .sizeIn(minWidth = Theme.Size.unreadBadge, minHeight = Theme.Size.unreadBadge)
                    .clip(CircleShape)
                    .background(fill)
                    .padding(horizontal = Theme.Spacing.tight + Theme.Spacing.withinRun),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    room.unreadNotifyingMessages.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent.foreground,
                )
            }
        } else if (room.hasUnread) {
            Box(Modifier.size(Theme.Size.unreadDot).clip(CircleShape).background(fill))
        }
    }
}

@Composable
private fun Face(room: RoomSummary) {
    RoomAvatar(
        name = room.name,
        url = room.avatarUrl,
        size = Theme.Size.roomAvatar,
        shape = if (room.isSpace) AvatarShape.ROUNDED_SQUARE else AvatarShape.CIRCLE,
        seed = room.avatarSeed,
        faces = room.faces,
    )
}

@Composable
private fun InviteRow(room: RoomSummary, onAccept: () -> Unit, onDecline: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalAlignment = Alignment.Top,
    ) {
        Face(room)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                room.invitedBy?.let { stringResource(R.string.room_list_invite_from, it) }
                    ?: stringResource(R.string.room_list_invite_unattributed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
                SecondaryActionButton(onClick = onDecline, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.room_list_invite_decline))
                }
                PrimaryActionButton(onClick = onAccept, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.room_list_invite_accept))
                }
            }
        }
    }
}

/** Что можно сделать с чатом из списка — то, что на iOS под свайпами. */
private class RoomActions(
    val onRead: (RoomSummary) -> Unit,
    val onUnread: (RoomSummary) -> Unit,
    val onPin: (RoomSummary) -> Unit,
    val onArchive: (RoomSummary) -> Unit,
    val onLeave: (RoomSummary) -> Unit,
)

/**
 * Меню долгого нажатия на строке: прочитано/непрочитано, закрепить, в архив, выйти. Тот же набор, что
 * у свайпов на iOS (`RoomListSwipeActions`); жесты-свайпы — следующим шагом.
 */
@Composable
private fun RoomMenu(room: RoomSummary, expanded: Boolean, onDismiss: () -> Unit, actions: RoomActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val entries = listOf(
            Triple(if (room.hasUnread) R.string.room_list_swipe_mark_read else R.string.room_list_swipe_mark_unread,
                if (room.hasUnread) Icons.Outlined.MarkChatRead else Icons.Outlined.MarkChatUnread,
                if (room.hasUnread) actions.onRead else actions.onUnread),
            Triple(if (room.isPinned) R.string.room_list_swipe_unpin else R.string.room_list_swipe_pin, Icons.Outlined.PushPin, actions.onPin),
            Triple(if (room.isArchived) R.string.room_list_swipe_unarchive else R.string.room_list_swipe_archive, Icons.Outlined.Archive, actions.onArchive),
        )
        entries.forEach { (label, icon, action) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = { onDismiss(); action(room) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.room_list_swipe_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); actions.onLeave(room) },
        )
    }
}

@Composable
private fun ArchiveRow(count: Int, hasUnread: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Theme.Size.roomAvatar).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.room_list_archive_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (hasUnread) LocalAccent.current.control else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ArchiveHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.tight, vertical = Theme.Spacing.close),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
        }
        Text(stringResource(R.string.room_list_archive_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
