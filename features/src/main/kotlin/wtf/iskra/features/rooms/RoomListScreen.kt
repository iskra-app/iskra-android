package wtf.iskra.features.rooms

import wtf.iskra.core.rooms.ChatFolder
import wtf.iskra.features.support.secondaryClick
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import wtf.iskra.core.rooms.ChatFolders
import wtf.iskra.core.rooms.FoldersState
import wtf.iskra.core.search.SearchHit
import wtf.iskra.core.search.SearchState
import wtf.iskra.core.search.SearchStore
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Unarchive
import wtf.iskra.design.PillField
import wtf.iskra.features.support.SwipeAction
import wtf.iskra.features.support.SwipeActions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import wtf.iskra.core.rooms.PrivacyStatus
import wtf.iskra.core.rooms.LastMessageMark
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import wtf.iskra.core.rooms.RoomListStore
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.core.rooms.UnreadEmphasis
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.SecondaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.AvatarShape
import wtf.iskra.features.support.RoomAvatar
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
@OptIn(ExperimentalMaterial3Api::class)
fun RoomListScreen(
    store: RoomListStore,
    onOpen: (roomId: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** Что показать над списком — «старые сообщения заперты» и подобное. */
    banner: @Composable () -> Unit = {},
    /** Поиск по сообщениям — под совпавшими названиями; null — только названия. */
    search: SearchStore? = null,
    onOpenAt: (roomId: String, eventId: String) -> Unit = { room, _ -> onOpen(room) },
    /** Меню аккаунтов по долгому нажатию на шестерёнку: открыто ли и как закрыть. Null — его нет. */
    accountMenu: (@Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit)? = null,
    /** Точка на шестерёнке: в настройках ждёт новое — версия приложения. */
    settingsDot: Boolean = false,
    /** Папки аккаунта: вкладки над списком и «В папку» в меню строки. Null — без папок. */
    folders: ChatFolders? = null,
    /** Долгое нажатие на полосу папок — к их настройкам. */
    onEditFolders: () -> Unit = {},
    /** «Новый чат» (карандаш в шапке): личный чат, комната, каталог. Null — кнопки нет. */
    onNewConversation: (() -> Unit)? = null,
    /** Открытая рядом комната (широкий экран, список — колонка): её строка выделена. Null — список во весь экран. */
    selectedId: String? = null,
    /** Лента комнаты для заглядывания по долгому нажатию (`ChatPeek`). Null — по долгому нажатию обычное меню. */
    peek: ((String) -> wtf.iskra.core.timeline.TimelineStore?)? = null,
    ownUserId: String = "",
) {
    val state by store.state.collectAsStateWithLifecycle()
    val found by (search?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(SearchState()) }).collectAsStateWithLifecycle()
    val typing by store.typingRoomIds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(query) { search?.search(query) }
    var showingArchive by rememberSaveable { mutableStateOf(false) }
    val folderState by (folders?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(FoldersState()) }).collectAsStateWithLifecycle()
    LaunchedEffect(folders) { folders?.load() }
    // Выбранной папки больше нет (удалили здесь или на другом устройстве) — это «Все», без особого слова.
    var folderId by rememberSaveable { mutableStateOf(ALL) }
    val folder = folderState.folders.firstOrNull { it.id == folderId }
    var filing by remember { mutableStateOf<RoomSummary?>(null) }
    var leaving by remember { mutableStateOf<RoomSummary?>(null) }
    BackHandler(enabled = showingArchive) { showingArchive = false }

    // Поиск — по уже собранным строкам: имя чата, без похода на сервер. Поиск по сообщениям — свой пункт.
    val source = when {
        showingArchive -> state.archivedRooms
        folder != null -> folder.rooms(state.rooms, state.archivedRooms)
        else -> state.rooms
    }
    val shown = remember(source, query) {
        val needle = query.trim()
        if (needle.isEmpty()) source else source.filter { it.name.contains(needle, ignoreCase = true) }
    }
    // Заглядывание: комната, на строку которой нажали и держат (`ChatPeek`).
    var peeking by remember { mutableStateOf<RoomSummary?>(null) }
    val actions = RoomActions(
        onRead = { scope.launch { store.markRead(it.id) } },
        onUnread = { scope.launch { store.markUnread(it.id) } },
        onPin = { scope.launch { store.setPinned(it.id, !it.isPinned) } },
        onArchive = { scope.launch { store.setArchived(it.id, !it.isArchived) } },
        onLeave = { leaving = it },
        onFolders = if (folderState.folders.isNotEmpty()) ({ filing = it }) else null,
    )

    peeking?.let { room ->
        val timeline = remember(room.id) { peek?.invoke(room.id) }
        if (timeline != null) {
            val peekActions = buildList {
                add(wtf.iskra.features.room.PeekAction(R.string.room_list_peek_open, Icons.AutoMirrored.Outlined.OpenInNew) { onOpen(room.id) })
                if (room.hasUnread) add(wtf.iskra.features.room.PeekAction(R.string.room_list_swipe_mark_read, Icons.Outlined.MarkChatRead) { actions.onRead(room) })
                else add(wtf.iskra.features.room.PeekAction(R.string.room_list_peek_mark_unread, Icons.Outlined.MarkChatUnread) { actions.onUnread(room) })
                add(wtf.iskra.features.room.PeekAction(if (room.isPinned) R.string.room_list_swipe_unpin else R.string.room_list_swipe_pin, Icons.Outlined.PushPin) { actions.onPin(room) })
                add(wtf.iskra.features.room.PeekAction(if (room.isArchived) R.string.room_list_swipe_unarchive else R.string.room_list_swipe_archive, Icons.Outlined.Archive) { actions.onArchive(room) })
                actions.onFolders?.let { toFolders -> add(wtf.iskra.features.room.PeekAction(R.string.folders_add_to_folder, Icons.Outlined.Folder) { toFolders(room) }) }
                add(wtf.iskra.features.room.PeekAction(R.string.room_list_swipe_delete, Icons.Outlined.Delete) { actions.onLeave(room) })
            }
            wtf.iskra.features.room.ChatPeek(room, timeline, ownUserId, peekActions, onOpen = { peeking = null; onOpen(room.id) }, onDismiss = { peeking = null })
        } else {
            peeking = null
        }
    }

    // Панель тонирует себя, когда под неё уезжает список: так Material показывает, что выше есть ещё.
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Column(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).nestedScroll(scroll.nestedScrollConnection)) {
        if (showingArchive) {
            ArchiveHeader(onBack = { showingArchive = false }, scroll = scroll)
        } else {
            Header(
                status = when {
                    state.isUnreachable -> R.string.room_list_waiting_for_network
                    state.isUpdating -> R.string.room_list_updating
                    else -> null
                },
                onSettings = onSettings,
                accountMenu = accountMenu,
                scroll = scroll,
                settingsDot = settingsDot,
                onNewConversation = onNewConversation,
            )
        }
        if (!showingArchive) banner()
        if (!showingArchive && folderState.folders.isNotEmpty()) {
            FolderTabs(
                folders = folderState.folders,
                rooms = state.rooms,
                archived = state.archivedRooms,
                selection = if (folder == null) ALL else folderId,
                onSelect = { folderId = it },
                onEdit = onEditFolders,
            )
        }

        when {
            state.isLoadingInitialRooms -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current.control)
            }
            showingArchive && source.isEmpty() -> Empty(R.string.room_list_archive_empty_title, R.string.room_list_archive_empty_message)
            folder != null && !showingArchive && source.isEmpty() -> Empty(R.string.folders_empty_title, R.string.folders_empty_message)
            source.isEmpty() && state.archivedRooms.isEmpty() -> Empty(R.string.room_list_empty_title, R.string.room_list_empty_message)
            else -> {
                val now = Instant.now()
                LazyColumn(Modifier.fillMaxSize()) {
                    // Поиск — первым элементом списка, а не прибит к заголовку: уезжает вместе с чатами, а
                    // вернуться к нему — прокрутить наверх, как в Telegram. Над архивом.
                    if (source.isNotEmpty()) {
                        item(key = "search") {
                            PillField(
                                value = query,
                                onValueChange = { query = it },
                                // «Поиск», а не «по сообщениям»: ищутся и названия чатов, и сообщения.
                                placeholder = stringResource(R.string.room_list_search_prompt),
                                leading = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
                            )
                        }
                    }
                    // Архив — строкой над списком, как в Telegram: смахнутые чаты никуда не делись.
                    if (!showingArchive && folder == null && query.isBlank() && state.archivedRooms.isNotEmpty()) {
                        item(key = "archive") {
                            ArchiveRow(state.archivedRooms.size, state.archivedRooms.any { it.hasUnread }) { showingArchive = true }
                        }
                    }
                    items(shown, key = { it.id }) { room ->
                        // Строка на экране — список заглядывает в её чат (`RoomPeek`), и уходит, когда строка ушла.
                        DisposableEffect(room.id) {
                            store.rowAppeared(room.id)
                            onDispose { store.rowDisappeared(room.id) }
                        }
                        if (room.isInvitation) {
                            InviteRow(
                                room = room,
                                onAccept = { scope.launch { store.accept(room.id) } },
                                onDecline = { scope.launch { store.decline(room.id) } },
                            )
                        } else {
                            RoomRow(room = room, now = now, typing = room.id in typing, actions = actions, selected = room.id == selectedId, onHold = if (peek != null) ({ peeking = room }) else null, onOpen = { onOpen(room.id) })
                        }
                    }
                    // Сообщения — под названиями: сначала «где», потом «что сказано».
                    if (query.isNotBlank()) {
                        val names = (state.rooms + state.archivedRooms).associate { it.id to it.name }
                        items(found.hits, key = { "hit:" + it.id }) { hit ->
                            SearchHitRow(hit, names[hit.roomId], now) { onOpenAt(hit.roomId, hit.id) }
                        }
                        if (found.foundNothing && shown.isEmpty()) {
                            item(key = "nothing") { Empty(R.string.room_search_empty_title, null) }
                        }
                    }
                }
            }
        }
    }

    filing?.let { room ->
        FolderMembership(room, folderState.folders, folders, onDismiss = { filing = null })
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
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
private fun Header(
    status: Int?,
    onSettings: () -> Unit,
    accountMenu: (@Composable (Boolean, () -> Unit) -> Unit)? = null,
    scroll: androidx.compose.material3.TopAppBarScrollBehavior? = null,
    settingsDot: Boolean = false,
    onNewConversation: (() -> Unit)? = null,
) {
    var showsAccounts by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(stringResource(R.string.room_list_title))
                if (status != null) {
                    Text(
                        stringResource(status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            if (onNewConversation != null) {
                IconButton(onClick = onNewConversation) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.room_list_new_conversation))
                }
            }
            // Касание — настройки, долгое — аккаунты, как на iOS. Кнопка своя: у `IconButton` нет долгого.
            Box {
                Box(
                    Modifier
                        .size(Theme.Size.minimumTapTarget)
                        .clip(CircleShape)
                        .combinedClickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.settings_title),
                            onClick = onSettings,
                            onLongClick = accountMenu?.let { { showsAccounts = true } },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.BadgedBox(badge = { if (settingsDot) androidx.compose.material3.Badge() }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
                accountMenu?.invoke(showsAccounts) { showsAccounts = false }
            }
        },
        windowInsets = WindowInsets(0),
        scrollBehavior = scroll,
    )
}

@Composable
private fun Empty(title: Int, message: Int?) {
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
            if (message != null) {
                Text(
                    stringResource(message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Что читает TalkBack вместо картинки строки: имя, закрытый ли, закреплён ли, черновик или последнее сообщение, потом *оба* — число
 * непрочитанных и упоминания, если оба верны. Раньше это было «или»: упоминание глушило счёт, и комната с двенадцатью непрочитанными и
 * одним упоминанием показывала красное «12», а голос говорил «1 упоминание». Порт `accessibilityLabel`.
 */
@Composable
private fun rowDescription(room: RoomSummary): String {
    val parts = mutableListOf(room.name)
    if (room.privacy == wtf.iskra.core.rooms.PrivacyStatus.PROTECTED) parts += stringResource(R.string.room_list_private_chat)
    if (room.isPinned) parts += stringResource(R.string.room_list_pinned)
    val draft = room.draft
    val message = room.lastMessage
    if (draft != null) {
        parts += stringResource(R.string.room_list_draft, draft.replace('\n', ' '))
    } else if (message != null) {
        parts += if (!room.isDirectMessage && room.lastSender != null) "${room.lastSender}: $message" else message
    }
    if (room.hasUnreadCount) {
        parts += pluralStringResource(R.plurals.room_list_unread, room.unreadNotifyingMessages, room.unreadNotifyingMessages)
    } else if (room.hasUnread) {
        parts += stringResource(R.string.room_list_has_unread)
    }
    if (room.isMentioned) parts += pluralStringResource(R.plurals.room_list_mentions, room.mentionCount, room.mentionCount)
    if (room.hasUnreadReactions) parts += pluralStringResource(R.plurals.room_list_reactions, room.unreadReactionCount, room.unreadReactionCount)
    return parts.joinToString(", ")
}

@Composable
internal fun RoomRow(room: RoomSummary, now: Instant, typing: Boolean, actions: RoomActions, selected: Boolean = false, onHold: (() -> Unit)? = null, onOpen: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val description = rowDescription(room)
    // Мышь: строка под курсором подсвечена, правая кнопка — как долгое нажатие.
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    // Как на iOS: вправо — прочитано и закрепить, влево — архив и удалить. Удаление спрашивает.
    // Цвета — роли схемы: смысл тот же (прочитано — акцентом, удалить — ошибкой), тон — Material.
    val leading = listOf(
        if (room.hasUnread) {
            SwipeAction(stringResource(R.string.room_list_swipe_mark_read), Icons.Filled.MarkEmailRead, scheme.primary, scheme.onPrimary) { actions.onRead(room) }
        } else {
            SwipeAction(stringResource(R.string.room_list_swipe_mark_unread), Icons.Filled.MarkEmailUnread, scheme.primary, scheme.onPrimary) { actions.onUnread(room) }
        },
        SwipeAction(
            stringResource(if (room.isPinned) R.string.room_list_swipe_unpin else R.string.room_list_swipe_pin),
            Icons.Filled.PushPin,
            scheme.tertiary,
            scheme.onTertiary,
        ) { actions.onPin(room) },
    )
    val trailing = listOf(
        SwipeAction(
            stringResource(if (room.isArchived) R.string.room_list_swipe_unarchive else R.string.room_list_swipe_archive),
            if (room.isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
            scheme.secondary,
            scheme.onSecondary,
        ) { actions.onArchive(room) },
        SwipeAction(stringResource(R.string.room_list_swipe_delete), Icons.Filled.Delete, scheme.error, scheme.onError) { actions.onLeave(room) },
    )
    SwipeActions(leading, trailing) {
    Box {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hover)
            .background(if (selected) scheme.secondaryContainer else if (hovered) scheme.surfaceContainerHigh else Color.Transparent)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .combinedClickable(onClick = onOpen, onClickLabel = room.name, onLongClick = { if (onHold != null) onHold() else menu = true })
            .secondaryClick { if (onHold != null) onHold() else menu = true }
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(room)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
                // Имя и замок занимают всё, что осталось слева; время и отметки — у правого края.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
                    Text(
                        room.name,
                        style = MaterialTheme.typography.titleMedium,
                        // Как `RoomRow` на iOS: имя всегда полужирное, непрочитанность говорит значок, не шрифт.
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // Закрытый замок — единственный словарь шифрования на этом экране.
                    if (room.privacy == PrivacyStatus.PROTECTED) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.room_list_private_chat),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Theme.Size.rowMark),
                        )
                    }
                }
                if (room.isPinned) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.room_list_pinned),
                        tint = LocalAccent.current.control,
                        modifier = Modifier.size(Theme.Size.rowMark),
                    )
                }
                if (!typing) room.lastMessageMark?.let { LastMark(it) }
                room.lastActivity?.let { at ->
                    Text(
                        RoomTime.label(at, now, ZoneId.systemDefault(), locale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Сверху, а не по центру: превью в две строки, значок в одну — иначе он съезжал бы к середине фразы.
            Row(verticalAlignment = Alignment.Top) {
                Preview(room, typing, Modifier.weight(1f))
                Spacer(Modifier.width(Theme.Spacing.close))
                Badge(room)
            }
        }
    }
    RoomMenu(room, menu, onDismiss = { menu = false }, actions)
    }
    }
}

/** Найденное сообщение: чат и время, кто сказал и сами слова. Нажатие открывает чат на нём. */
@Composable
private fun SearchHitRow(hit: SearchHit, roomName: String?, now: Instant, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(roomName ?: hit.senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(RoomTime.label(hit.timestamp, now, ZoneId.systemDefault(), locale), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(hit.senderName, style = MaterialTheme.typography.labelMedium, color = LocalAccent.current.control, maxLines = 1)
        Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Две строки под именем, **зарезервированные** всегда: список, где строки прыгают в высоту от
 * длины последнего сообщения, читается как дёргающийся. В группе первая строка — кто написал.
 */
@Composable
private fun Preview(room: RoomSummary, typing: Boolean, modifier: Modifier) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    val draft = room.draft
    when {
        // Как в заголовке комнаты: пока пишут — это и есть последнее, что там происходит.
        typing -> Text(
            stringResource(R.string.room_typing),
            modifier = modifier,
            style = style,
            color = LocalAccent.current.control,
            minLines = 2,
            maxLines = 2,
        )
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
    if (!room.hasUnread && !room.isMentioned && !room.hasUnreadReactions) return
    val accent = LocalAccent.current
    val fill = when (room.unreadEmphasis) {
        UnreadEmphasis.ORDINARY -> accent.control
        UnreadEmphasis.MUTED -> MaterialTheme.colorScheme.outline
    }
    val ink = when (room.unreadEmphasis) {
        UnreadEmphasis.ORDINARY -> accent.foreground
        UnreadEmphasis.MUTED -> MaterialTheme.colorScheme.surface
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Отреагировали: сердечко само по себе и никогда не число — реакция отвечает на уже прочитанное.
        if (room.hasUnreadReactions) {
            Box(
                Modifier.size(Theme.Size.unreadBadge).clip(CircleShape).background(if (room.unreadEmphasis == UnreadEmphasis.MUTED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(Theme.Size.rowMark - Theme.Spacing.tight))
            }
        }
        if (room.isMentioned) {
            Box(
                Modifier.size(Theme.Size.unreadBadge).clip(CircleShape).background(fill),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.room_list_mention_mark), style = MaterialTheme.typography.labelMedium, color = ink)
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
                    color = ink,
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
        // Пространство — полка комнат, а не тот, на кого смотрят: у него нет существа, только квадрат и буквы.
        seed = if (room.isSpace) null else room.avatarSeed,
        faces = room.faces,
    )
}

@Composable
internal fun InviteRow(room: RoomSummary, onAccept: () -> Unit, onDecline: () -> Unit) {
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
internal class RoomActions(
    val onRead: (RoomSummary) -> Unit,
    val onUnread: (RoomSummary) -> Unit,
    val onPin: (RoomSummary) -> Unit,
    val onArchive: (RoomSummary) -> Unit,
    val onLeave: (RoomSummary) -> Unit,
    /** «В папку» — только когда у аккаунта есть папки. */
    val onFolders: ((RoomSummary) -> Unit)? = null,
)

/**
 * Меню долгого нажатия на строке: прочитано/непрочитано, закрепить, в архив, выйти. Тот же набор, что
 * у свайпов на iOS (`RoomListSwipeActions`), и у смахиваний здесь же.
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
        actions.onFolders?.let { toFolders ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_add_to_folder)) },
                leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                onClick = { onDismiss(); toFolders(room) },
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
internal fun ArchiveRow(count: Int, hasUnread: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(Theme.Size.roomAvatar).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
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
@OptIn(ExperimentalMaterial3Api::class)
private fun ArchiveHeader(onBack: () -> Unit, scroll: androidx.compose.material3.TopAppBarScrollBehavior? = null) {
    TopAppBar(
        title = { Text(stringResource(R.string.room_list_archive_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
        },
        windowInsets = WindowInsets(0),
        scrollBehavior = scroll,
    )
}

/**
 * В какие папки входит чат — отметкой, как в «О чате» (`FolderMembershipView`). Отмеченный лежит в папке, даже
 * если не подходит под правила; снятый — не лежит никогда, даже если подходит.
 */
@Composable
private fun FolderMembership(room: RoomSummary, all: List<ChatFolder>, folders: ChatFolders?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_membership_title)) },
        text = {
            Column {
                all.forEach { folder ->
                    val isIn = folder.contains(room)
                    Row(
                        Modifier.fillMaxWidth().clickable { scope.launch { folders?.set(room.id, folder.id, !isIn) } }.padding(vertical = Theme.Spacing.tight),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = isIn, onCheckedChange = null)
                        Text(folder.name, modifier = Modifier.padding(start = Theme.Spacing.normal))
                    }
                }
                Text(
                    stringResource(R.string.folders_membership_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Theme.Spacing.close),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_done)) } },
    )
}

/** Докуда дошло своё последнее сообщение — рядом со временем, как место Telegram (`DeliveryTick`). */
@Composable
private fun LastMark(mark: LastMessageMark) {
    val (icon, label, tint) = when (mark) {
        LastMessageMark.SENDING -> Triple(Icons.Outlined.Schedule, R.string.message_delivery_sending, MaterialTheme.colorScheme.onSurfaceVariant)
        LastMessageMark.SENT -> Triple(Icons.Filled.Check, R.string.message_delivery_sent, LocalAccent.current.control)
        LastMessageMark.READ -> Triple(Icons.Filled.Visibility, R.string.message_delivery_read, LocalAccent.current.control)
        LastMessageMark.FAILED -> Triple(Icons.Filled.ErrorOutline, R.string.message_delivery_failed, MaterialTheme.colorScheme.error)
    }
    Icon(icon, contentDescription = stringResource(label), tint = tint, modifier = Modifier.size(Theme.Size.rowMark))
}
