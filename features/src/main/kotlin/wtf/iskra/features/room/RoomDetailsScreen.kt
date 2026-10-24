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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Search
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
    /** «Поиск» в плитках: закрыть «О чате» и открыть поиск по переписке. Null — плитки нет. */
    onSearch: (() -> Unit)? = null,
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

            // Плитки под лицом (`tiles` в `RoomInfoView`): поиск, звук, пригласить, поделиться, код. Ссылка — на человека в личном чате, на открытую комнату в комнате.
            val own = wtf.iskra.features.support.LocalOwnUserId.current
            val link: wtf.iskra.core.rooms.MatrixLink? = when {
                state.isDirect -> state.members.firstOrNull { it.userId != own }?.let { wtf.iskra.core.rooms.MatrixLink.User(it.userId) }
                !state.isPublic -> null
                else -> state.canonicalAlias?.let { wtf.iskra.core.rooms.MatrixLink.Room(it) }
                    ?: wtf.iskra.core.rooms.MatrixLink.Room(store.roomId, listOfNotNull(store.roomId.substringAfter(':', "").takeIf { it.isNotEmpty() }))
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            val muted = state.alerts == RoomAlerts.MUTED
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy, Alignment.CenterHorizontally),
            ) {
                if (onSearch != null) ProfileTile(R.string.profile_search, Icons.Outlined.Search) { onSearch() }
                ProfileTile(
                    if (muted) R.string.profile_unmute else R.string.profile_mute,
                    if (muted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                    enabled = !isOffline,
                ) { scope.launch { store.setAlerts(if (muted) RoomAlerts.ALL else RoomAlerts.MUTED) } }
                if (!state.isDirect && state.permissions.canInvite) {
                    ProfileTile(R.string.room_manage_invite, Icons.Outlined.PersonAdd, enabled = !isOffline) { inviting = true }
                }
                if (link != null) {
                    ProfileTile(R.string.profile_share, Icons.Outlined.Share) {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, link.url)
                        runCatching { context.startActivity(android.content.Intent.createChooser(send, null)) }
                    }
                    ProfileTile(R.string.qr_title, Icons.Outlined.QrCode2) { showingCode = true }
                }
            }

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
                // Сказано один раз под строкой, а не оставлено читателю как серая кнопка: выключенный переключатель сам ничего не объясняет.
                val blockedNow by blocked.state.collectAsStateWithLifecycle()
                if (other.userId in blockedNow.blockedUserIds) Hint(R.string.profile_blocked_notice)
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

    if (showingCode) {
        val own = wtf.iskra.features.support.LocalOwnUserId.current
        val qrLink: wtf.iskra.core.rooms.MatrixLink? = when {
            state.isDirect -> state.members.firstOrNull { it.userId != own }?.let { wtf.iskra.core.rooms.MatrixLink.User(it.userId) }
            else -> state.canonicalAlias?.let { wtf.iskra.core.rooms.MatrixLink.Room(it) }
                ?: wtf.iskra.core.rooms.MatrixLink.Room(store.roomId, listOfNotNull(store.roomId.substringAfter(':', "").takeIf { it.isNotEmpty() }))
        }
        if (qrLink != null) {
            wtf.iskra.features.support.QrCardSheet(
                name = state.name,
                subtitle = state.canonicalAlias ?: (qrLink as? wtf.iskra.core.rooms.MatrixLink.User)?.userId ?: store.roomId,
                link = qrLink,
                onDismiss = { showingCode = false },
            )
        }
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

/** Круглая плитка с подписью под лицом чата (`ProfileActionTile`). */
@Composable
private fun ProfileTile(title: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    val label = stringResource(title)
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Theme.Radius.control)).clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
    ) {
        Box(
            Modifier.size(Theme.Size.profileAction).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = wtf.iskra.design.LocalAccent.current.control.copy(alpha = if (enabled) 1f else DISABLED))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private const val DISABLED = 0.4f
