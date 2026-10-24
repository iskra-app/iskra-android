package wtf.iskra.features.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.notifications.RoomAlerts
import wtf.iskra.core.rooms.PrivacyStatus
import wtf.iskra.core.rooms.RoomDetailsStore
import wtf.iskra.core.rooms.RoomMemberSummary
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.settings.Choice
import wtf.iskra.features.settings.Group
import wtf.iskra.features.settings.SectionTitle
import wtf.iskra.features.settings.rowColors
import wtf.iskra.features.support.Avatar
import wtf.iskra.features.support.RoomAvatar

/**
 * «О чате» (`RoomInfoView` на iOS): кто это, как он зовётся на этом устройстве, сколько о нём говорить
 * и кто в нём; а кому комната позволяет — роли, приглашения, удаление и блокировка, история для новых людей и адрес.
 * Открывается нажатием на шапку переписки.
 *
 * [isOffline] — без сети чат читают и пишут в него, но не меняют: правила здесь не трогаются.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun RoomDetailsScreen(
    store: RoomDetailsStore,
    isOffline: Boolean,
    onBack: () -> Unit,
    onLeave: () -> Unit,
    /** Блокировка аккаунта (`ignore`) — человека личного чата и участника группы. Null — без неё. */
    blocked: wtf.iskra.core.session.BlockStore? = null,
) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.load() }
    DisposableEffect(store) { onDispose { store.stop() } }
    BackHandler(onBack = onBack)
    var leaving by remember { mutableStateOf(false) }
    var query by rememberSaveable(store.roomId) { mutableStateOf("") }
    var picked by remember { mutableStateOf<RoomMemberSummary?>(null) }
    var inviting by remember { mutableStateOf(false) }
    // Правка — своим экраном поверх этого, а стор остаётся здесь: подписка на комнату нужна обоим.
    var editing by rememberSaveable(store.roomId) { mutableStateOf(false) }
    val canEdit = !state.isDirect && state.permissions.canEditAnything
    var showingCode by remember { mutableStateOf(false) }

    if (editing && canEdit) {
        RoomEdit(store, isOffline, onBack = { editing = false })
    } else Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text(stringResource(R.string.room_details_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            // Карандаш — только тому, кому комната что-то менять разрешает (`RoomInfoView` на iOS).
            actions = {
                // Код у открытой комнаты с адресом: по нему входят, а закрытой ссылка ничего не даст.
                if (!state.isDirect && state.isPublic && state.canonicalAlias != null) {
                    IconButton(onClick = { showingCode = true }) {
                        Icon(Icons.Outlined.QrCode2, contentDescription = stringResource(R.string.qr_title))
                    }
                }
                if (canEdit) {
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.room_manage_edit))
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            Portrait(store)

            if (state.isDirect) LocalName(store)

            SectionTitle(R.string.room_manage_alerts)
            val current = state.alerts
            Group(
                *RoomAlerts.entries.map { option ->
                    @Composable {
                        Choice(
                            title = when (option) {
                                RoomAlerts.ALL -> R.string.room_manage_alerts_all
                                RoomAlerts.MENTIONS -> R.string.room_manage_alerts_mentions
                                RoomAlerts.MUTED -> R.string.room_manage_alerts_muted
                            },
                            selected = option == current,
                        ) { if (!isOffline) scope.launch { store.setAlerts(option) } }
                    }
                }.toTypedArray(),
            )
            if (isOffline) Hint(R.string.room_manage_offline)

            if (!state.isDirect && state.members.isNotEmpty()) {
                SectionTitle(R.string.room_tabs_members)
                // Поиск — когда людей столько, что листать дольше, чем набрать.
                if (state.members.size > SEARCH_FROM) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.profile_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
                    )
                }
                val needle = query.trim()
                val shown = if (needle.isEmpty()) state.members else state.members.filter {
                    it.name.contains(needle, ignoreCase = true) || it.userId.contains(needle, ignoreCase = true)
                }
                if (state.permissions.canInvite && !isOffline) {
                    Group({
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.room_manage_invite)) },
                            leadingContent = { Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = wtf.iskra.design.LocalAccent.current.control) },
                            modifier = Modifier.clickable { inviting = true },
                            colors = rowColors(),
                        )
                    })
                }
                Group(
                    *shown.take(MEMBERS_SHOWN).map { member ->
                        @Composable { Member(member) { picked = member } }
                    }.toTypedArray(),
                )
            }
            if (!state.isDirect) {
                BannedSection(store, state, isOffline)
            }
            // Личный чат: «Заблокировать» человека, с которым он, — тот же ряд, что на странице человека.
            val other = if (state.isDirect) state.members.firstOrNull { it.userId != wtf.iskra.features.support.LocalOwnUserId.current } else null
            if (other != null && blocked != null) {
                Group({ wtf.iskra.features.support.PersonBlockRow(blocked, other.userId, other.name, enabled = !isOffline) })
            }

            Group({
                ListItem(
                    headlineContent = { Text(stringResource(if (state.isDirect) R.string.room_manage_delete_chat else R.string.room_manage_leave)) },
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                    modifier = Modifier.clickable { leaving = true },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                        headlineColor = MaterialTheme.colorScheme.error,
                        leadingIconColor = MaterialTheme.colorScheme.error,
                    ),
                )
            })
        }
    }

    val alias = state.canonicalAlias
    if (showingCode && alias != null) {
        wtf.iskra.features.support.QrCardSheet(
            name = state.name,
            subtitle = alias,
            link = wtf.iskra.core.rooms.MatrixLink.Room(alias),
            onDismiss = { showingCode = false },
        )
    }
    picked?.let { member -> MemberSheet(store, member, isOffline, blocked, onDismiss = { picked = null }) }
    if (inviting) InviteSheet(store, onDismiss = { inviting = false })

    if (leaving) {
        val direct = state.isDirect
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(stringResource(if (direct) R.string.room_manage_delete_chat_question else R.string.room_manage_leave_question)) },
            text = { Text(stringResource(if (direct) R.string.room_manage_delete_chat_help else R.string.room_manage_leave_help)) },
            confirmButton = {
                TextButton(onClick = {
                    leaving = false
                    onLeave()
                }) {
                    Text(
                        stringResource(if (direct) R.string.room_manage_delete_chat else R.string.room_manage_leave),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.room_manage_cancel)) } },
        )
    }

    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = store::dismissFailure,
            confirmButton = { TextButton(onClick = store::dismissFailure) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(failure.message)) },
        )
    }
}

/** Лицо, имя, сколько людей, о чём чат и защищён ли он. */
@Composable
private fun Portrait(store: RoomDetailsStore) {
    val state by store.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().padding(Theme.Spacing.generous),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        RoomAvatar(state.name, state.avatarUrl, Theme.Size.avatarProfile, seed = state.avatarSeed, faces = state.faces)
        Text(state.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (!state.isDirect && state.memberCount > 0) {
            Text(
                pluralStringResource(R.plurals.room_participants, state.memberCount, state.memberCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.topic?.let { topic ->
            Text(topic, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        if (state.isLoaded) {
            ListItem(
                headlineContent = { Text(stringResource(state.privacy.explanation)) },
                // Замок закрыт только там, где переписку и правда не прочесть серверу.
                leadingContent = {
                    Icon(if (state.privacy == PrivacyStatus.PROTECTED) Icons.Outlined.Lock else Icons.Outlined.LockOpen, contentDescription = null)
                },
                colors = rowColors(),
            )
        }
    }
}

/**
 * Имя на этом устройстве — только у личного чата. Записывается по «Готово» и когда экран уходит:
 * прозвище на каждую букву пересобирало бы список чатов на каждую букву.
 */
@Composable
private fun LocalName(store: RoomDetailsStore) {
    val state by store.state.collectAsStateWithLifecycle()
    var text by rememberSaveable(store.roomId, state.localName) { mutableStateOf(state.localName.orEmpty()) }
    DisposableEffect(store) { onDispose { if (text.trim() != store.state.value.localName.orEmpty()) store.renameLocally(text) } }

    SectionTitle(R.string.room_local_name_header)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(state.serverName) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { store.renameLocally(text) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
    )
    Hint(R.string.room_local_name_help)
    if (state.localName != null) {
        TextButton(
            onClick = {
                text = ""
                store.renameLocally(null)
            },
            modifier = Modifier.padding(horizontal = Theme.Spacing.roomy),
        ) { Text(stringResource(R.string.room_local_name_reset, state.serverName)) }
    }
}

@Composable
private fun Member(member: RoomMemberSummary, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(member.name) },
        // Два «Бориса» в комнате — повод показать то, что подделать нельзя: Matrix ID.
        supportingContent = when {
            member.isNameAmbiguous -> { { Text(member.userId) } }
            member.role == RoomMemberSummary.Role.ADMINISTRATOR -> { { Text(stringResource(R.string.room_administrator)) } }
            member.role == RoomMemberSummary.Role.MODERATOR -> { { Text(stringResource(R.string.room_moderator)) } }
            else -> null
        },
        leadingContent = { Avatar(member.name, member.avatarUrl, Theme.Size.avatar, seed = member.userId) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = rowColors(),
    )
}

/** Больше — список прокручивали бы ради прокрутки. */
private const val MEMBERS_SHOWN = 100

/** С какого числа людей показывается поиск. */
private const val SEARCH_FROM = 8
