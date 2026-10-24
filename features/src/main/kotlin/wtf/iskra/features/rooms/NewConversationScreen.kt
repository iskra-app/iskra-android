package wtf.iskra.features.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import wtf.iskra.core.rooms.Contact
import wtf.iskra.core.rooms.ConversationException
import wtf.iskra.core.rooms.ConversationFailure
import wtf.iskra.core.rooms.Conversations
import wtf.iskra.core.rooms.MatrixLink
import wtf.iskra.core.rooms.PersonProfile
import wtf.iskra.core.rooms.PublicRoom
import wtf.iskra.core.rooms.RoomDetailsStore
import wtf.iskra.core.session.UserSession
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PillField
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.room.Hint
import wtf.iskra.features.settings.Group
import wtf.iskra.features.settings.SectionTitle
import wtf.iskra.features.settings.Toggle
import wtf.iskra.features.settings.rowColors
import wtf.iskra.features.support.Avatar
import wtf.iskra.features.support.QrScanner
import wtf.iskra.features.support.RoomAvatar

private enum class Page { CHATS, SCAN, LINK, NEW_ROOM, PUBLIC_ROOMS, JOIN_BY_ADDRESS }

/**
 * «Новый чат», как в Telegram: сверху поле для людей, под ним три пути в комнату, потом все, с кем у аккаунта уже есть
 * разговор, по буквам. Порт `NewChatView`.
 *
 * Поле — путь к тому, кого ещё нет в списке. Ищет сразу в двух местах: среди разговоров, что уже здесь (отвечают
 * мгновенно), и в каталоге пользователей сервера (отвечает чуть позже); а полностью набранный Matrix ID — всегда тот,
 * кому можно написать, знает о нём каталог или нет: сервер перечисляет лишь тех, кого хочет. Все пути отсюда — комната,
 * которую надо показать, и все выходят через [onOpen].
 *
 * Сканер QR читает ссылку на человека или комнату и ведёт на её страницу ([MatrixLinkScreen]): сразу в комнату не входит.
 */
@Composable
fun NewConversationScreen(session: UserSession, onOpen: (roomId: String) -> Unit, onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(Page.CHATS) }
    // Прочитанная ссылка — текстом, чтобы пережить поворот; страница читает её заново.
    var scanned by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(onBack = { if (page == Page.CHATS) onClose() else page = Page.CHATS })
    val back = { if (page == Page.CHATS) onClose() else page = Page.CHATS }
    val conversations = session.conversations
    when (page) {
        Page.SCAN -> Screen(R.string.qr_scan, back) {
            // Вместо сканера, а не поверх него: «назад» со страницы человека ведёт в «Новый чат», а не к камере, что прочла бы его снова.
            QrScanner(onFound = { link ->
                scanned = link.deepLink
                page = Page.LINK
            })
        }
        Page.LINK -> {
            val link = scanned?.let(MatrixLink::parse)
            if (link == null) page = Page.CHATS else MatrixLinkScreen(conversations, link, onOpen, onBack = { page = Page.CHATS }, blocked = session.blocked)
        }
        Page.CHATS -> Chats(session, conversations, onOpen, onBack = back, onNavigate = { page = it })
        Page.NEW_ROOM -> NewRoom(conversations, onCreated = onOpen, onBack = back)
        Page.PUBLIC_ROOMS -> PublicRooms(session, conversations, onOpen, onBack = back)
        Page.JOIN_BY_ADDRESS -> JoinByAddress(conversations, onJoined = onOpen, onBack = back)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Screen(title: Int, onBack: () -> Unit, action: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text(stringResource(title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
            },
            actions = { action() },
            windowInsets = WindowInsets(0),
        )
        content()
    }
}

/** То, что не вышло, — словами из ядра; одна кнопка, потому что решать тут нечего. */
@Composable
internal fun FailureDialog(failure: ConversationFailure?, onDismiss: () -> Unit) {
    if (failure == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        text = { Text(stringResource(failure.message)) },
    )
}

/** Выполняет [block] и отдаёт сбой разговора, если он был; отмена идёт дальше. */
internal suspend fun attempt(block: suspend () -> Unit): ConversationFailure? = try {
    block()
    null
} catch (error: ConversationException) {
    error.failure
}

// MARK: Главная

@Composable
private fun Chats(
    session: UserSession,
    conversations: Conversations,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onNavigate: (Page) -> Unit,
) {
    val rooms by session.rooms.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<List<PersonProfile>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    // Тот, чей разговор открывается или заводится: его строка говорит об этом, и никакая другая не начнёт второй.
    var opening by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ConversationFailure?>(null) }

    val contacts = remember(rooms.rooms) { Contact.of(rooms.rooms) }
    val term = query.trim()
    val typedId = term.takeIf { RoomDetailsStore.isMatrixId(it) && it != session.userId }

    // Спрашивает каталог чуть позже, чем человек перестал печатать: `LaunchedEffect` отменяет прошлый вопрос, когда
    // запрос сменился, так что на экране отвечен только последний.
    LaunchedEffect(term) {
        searchFailed = false
        if (term.isEmpty()) {
            found = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(SEARCH_PAUSE_MS)
        try {
            found = conversations.searchPeople(term)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            found = emptyList()
            searchFailed = true
        }
        searching = false
    }

    fun write(userId: String) {
        if (opening != null) return
        opening = userId
        scope.launch {
            failure = attempt { onOpen(conversations.openDirectChat(userId)) }
            opening = null
        }
    }

    Screen(R.string.room_list_new_chat, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "search") {
                PillField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.room_list_new_search_prompt),
                    leading = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
                )
            }
            if (term.isEmpty()) {
                item(key = "actions") {
                    Column(Modifier.padding(top = Theme.Spacing.close)) {
                        Group(
                            { ActionRow(R.string.qr_scan, Icons.Outlined.QrCodeScanner) { onNavigate(Page.SCAN) } },
                            { ActionRow(R.string.room_list_new_create_room, Icons.Outlined.Groups) { onNavigate(Page.NEW_ROOM) } },
                            { ActionRow(R.string.room_list_new_public_rooms, Icons.Outlined.Language) { onNavigate(Page.PUBLIC_ROOMS) } },
                            { ActionRow(R.string.room_list_new_join_by_address, Icons.Outlined.Link) { onNavigate(Page.JOIN_BY_ADDRESS) } },
                        )
                    }
                }
                Contact.byLetter(contacts).forEach { (letter, people) ->
                    item(key = "letter:$letter") { LetterHeader(letter) }
                    items(people, key = { "contact:" + it.id }) { contact ->
                        PersonRow(contact.name, contact.id, contact.avatarUrl, contact.id, busy = false) { onOpen(contact.roomId) }
                    }
                }
            } else {
                val known = contacts
                    .filter { it.name.contains(term, ignoreCase = true) || it.id.contains(term, ignoreCase = true) }
                    .sortedBy { it.name.lowercase() }
                val knownIds = known.map { it.id }.toSet()
                val strangers = found.filter { it.id !in knownIds }
                if (known.isNotEmpty()) {
                    item(key = "known-title") { SectionTitle(R.string.room_list_new_your_chats) }
                    items(known, key = { "known:" + it.id }) { contact ->
                        PersonRow(contact.name, contact.id, contact.avatarUrl, contact.id, busy = false) { onOpen(contact.roomId) }
                    }
                }
                item(key = "server-title") { SectionTitle(R.string.room_list_new_on_server) }
                if (typedId != null && typedId !in knownIds && strangers.none { it.id == typedId }) {
                    item(key = "typed") {
                        PersonRow(typedId, stringResource(R.string.room_list_new_write_to), null, typedId, busy = opening == typedId) { write(typedId) }
                    }
                }
                items(strangers, key = { "found:" + it.id }) { person ->
                    PersonRow(person.name, person.id, person.avatarUrl, person.id, busy = opening == person.id) { write(person.id) }
                }
                item(key = "status") {
                    when {
                        searching -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.roomy), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LocalAccent.current.control)
                        }
                        searchFailed -> StatusText(R.string.room_list_new_search_failed)
                        strangers.isEmpty() && known.isEmpty() && typedId == null -> StatusText(R.string.room_list_new_nobody_found)
                    }
                }
            }
        }
    }
    FailureDialog(failure) { failure = null }
}

@Composable
private fun ActionRow(title: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = LocalAccent.current.control) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = rowColors(),
    )
}

@Composable
private fun LetterHeader(letter: String) {
    Text(
        letter,
        style = MaterialTheme.typography.titleSmall,
        color = LocalAccent.current.color,
        modifier = Modifier.padding(start = Theme.Spacing.generous + Theme.Spacing.close, end = Theme.Spacing.roomy, top = Theme.Spacing.roomy, bottom = Theme.Spacing.tight),
    )
}

@Composable
private fun StatusText(text: Int) {
    Text(
        stringResource(text),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close, vertical = Theme.Spacing.roomy),
    )
}

/** Человек: лицо, имя и серая строка под ним. */
@Composable
private fun PersonRow(name: String, subtitle: String, avatarUrl: String?, seed: String, busy: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Avatar(name, avatarUrl, Theme.Size.avatar, seed = seed) },
        trailingContent = { if (busy) Busy() },
        modifier = Modifier.clickable(enabled = !busy, onClick = onClick),
    )
}

// MARK: Новая комната

/**
 * Создать комнату: имя, тема и входит ли любой. Не спрашивается, шифровать ли: это следует из того, открыта ли комната, —
 * и фраза под переключателем так и говорит. Зачем два вопроса — один, см. [Conversations.createRoom].
 */
@Composable
private fun NewRoom(conversations: Conversations, onCreated: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var isPublic by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ConversationFailure?>(null) }
    val ready = name.isNotBlank()

    fun create() {
        if (!ready || creating) return
        creating = true
        scope.launch {
            failure = attempt { onCreated(conversations.createRoom(name, topic, isPublic)) }
            creating = false
        }
    }

    Screen(
        R.string.room_list_new_room,
        onBack,
        action = {
            if (creating) {
                Busy(Modifier.padding(Theme.Spacing.roomy))
            } else {
                TextButton(onClick = ::create, enabled = ready) { Text(stringResource(R.string.room_list_new_create)) }
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.room_manage_name_placeholder)) },
                singleLine = true,
                enabled = !creating,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
            )
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text(stringResource(R.string.room_manage_topic_placeholder)) },
                minLines = 1,
                maxLines = 4,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
            )
            Group({ Toggle(R.string.room_manage_open_to_anyone, R.string.room_manage_open_to_anyone_help, isPublic, { isPublic = it }, enabled = !creating) })
            // Чем комната станет, — тем же замком и теми же словами, что на «О чате», и переворачивается вместе с переключателем.
            Row(
                Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close),
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (isPublic) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(if (isPublic) R.string.room_list_new_will_not_be_encrypted else R.string.room_list_new_will_be_encrypted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Hint(R.string.room_list_new_open_means_unencrypted)
        }
    }
    FailureDialog(failure) { failure = null }
}

// MARK: Каталог

/** Публичный каталог сервера: что там есть, поле, чтобы сузить, и нажатие, чтобы войти. Уже вошедшую открывает, а не входит дважды. */
@Composable
private fun PublicRooms(session: UserSession, conversations: Conversations, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val directory = remember { conversations.publicRooms() }
    DisposableEffect(directory) { onDispose { directory.close() } }
    val state by directory.state.collectAsStateWithLifecycle()
    val rooms by session.rooms.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var joining by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<ConversationFailure?>(null) }
    val joined = remember(rooms.rooms) { rooms.rooms.filter { !it.isInvitation }.map { it.id }.toSet() }

    // Весь каталог первым, потом то, что набрано, — чуть позже, чем печать остановилась.
    LaunchedEffect(query.trim()) {
        if (query.isNotBlank()) delay(SEARCH_PAUSE_MS)
        directory.search(query)
    }

    fun open(room: PublicRoom) {
        if (room.id in joined) {
            onOpen(room.id)
            return
        }
        if (joining != null) return
        joining = room.id
        scope.launch {
            failure = attempt { onOpen(conversations.joinRoom(room)) }
            joining = null
        }
    }

    Screen(R.string.room_list_new_public_rooms, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "search") {
                PillField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.room_list_new_rooms_prompt),
                    leading = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
                )
            }
            items(state.rooms, key = { it.id }) { room ->
                LaunchedEffect(room.id, state.rooms.size) {
                    if (room.id == state.rooms.lastOrNull()?.id) directory.loadMore()
                }
                PublicRoomRow(room, busy = joining == room.id, enabled = joining == null && (room.isOpen || room.id in joined)) { open(room) }
            }
            item(key = "footer") {
                when {
                    state.isLoading -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.roomy), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalAccent.current.control)
                    }
                    state.failed -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close, vertical = Theme.Spacing.roomy),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.room_list_new_directory_failed), Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { scope.launch { directory.search(query) } }) { Text(stringResource(R.string.room_list_new_retry)) }
                    }
                    state.rooms.isEmpty() -> StatusText(R.string.room_list_new_no_rooms)
                }
            }
        }
    }
    FailureDialog(failure) { failure = null }
}

@Composable
private fun PublicRoomRow(room: PublicRoom, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(room.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                // Адрес и сколько людей — и, если в комнату не войти, почему нажатие молчит.
                val people = pluralStringResource(R.plurals.room_participants, room.memberCount, room.memberCount)
                val invite = stringResource(R.string.room_list_new_invite_only)
                Text(
                    listOfNotNull(room.alias, people, invite.takeUnless { room.isOpen }).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                room.topic?.takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            }
        },
        leadingContent = { RoomAvatar(room.title, room.avatarUrl, Theme.Size.avatar, seed = room.id) },
        trailingContent = { if (busy) Busy() },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

// MARK: По адресу

/** Войти по тому, что дали: `#имя:сервер` или ID комнаты. */
@Composable
private fun JoinByAddress(conversations: Conversations, onJoined: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var joining by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ConversationFailure?>(null) }
    val empty = address.isBlank()

    fun join() {
        if (empty || joining) return
        joining = true
        scope.launch {
            failure = attempt { onJoined(conversations.joinRoom(address)) }
            joining = false
        }
    }

    Screen(
        R.string.room_list_new_join_by_address,
        onBack,
        action = {
            if (joining) {
                Busy(Modifier.padding(Theme.Spacing.roomy))
            } else {
                TextButton(onClick = ::join, enabled = !empty) { Text(stringResource(R.string.room_list_new_join)) }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                placeholder = { Text(stringResource(R.string.room_list_new_address_placeholder)) },
                singleLine = true,
                enabled = !joining,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { join() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
            )
            Hint(R.string.room_list_new_address_help)
        }
    }
    FailureDialog(failure) { failure = null }
}

/** Крутилка в строке или на кнопке: по размеру значка, тонкая — не заслоняет то, что рядом. */
@Composable
internal fun Busy(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(Theme.Size.voiceButton), color = LocalAccent.current.control, strokeWidth = Theme.Size.waveformBar)
}

/** Пауза после последней буквы, прежде чем спрашивать сервер. */
private const val SEARCH_PAUSE_MS = 300L
