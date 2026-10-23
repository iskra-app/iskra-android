package wtf.iskra.features.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.rooms.HistoryAccess
import wtf.iskra.core.rooms.RoomDetailsState
import wtf.iskra.core.rooms.RoomDetailsStore
import wtf.iskra.core.rooms.RoomMemberSummary
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.settings.Choice
import wtf.iskra.features.settings.Group
import wtf.iskra.features.settings.SectionTitle
import wtf.iskra.features.settings.Toggle
import wtf.iskra.features.settings.rowColors
import wtf.iskra.features.support.Avatar

internal fun roleTitle(role: RoomMemberSummary.Role): Int = when (role) {
    RoomMemberSummary.Role.ADMINISTRATOR -> R.string.room_administrator
    RoomMemberSummary.Role.MODERATOR -> R.string.room_moderator
    RoomMemberSummary.Role.MEMBER -> R.string.room_manage_member
}

/**
 * Один человек: роль (кому комната даёт её менять), убрать и заблокировать. Показывается только то, что комната
 * разрешает *этому* аккаунту над *этим* человеком (`RoomDetailsStore.canManage`): серая кнопка обещала бы то, в чём сервер
 * уже отказал. Без сети — ничего из этого. Порт части `RoomInfoView` / `MemberDetailView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemberSheet(store: RoomDetailsStore, member: RoomMemberSummary, isOffline: Boolean, onDismiss: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Лист читает *текущее* состояние участника: роль сменилась — лист это видит.
    val current = state.members.firstOrNull { it.userId == member.userId } ?: member
    val roles = if (isOffline) emptyList() else store.assignableRoles(current)
    val manageable = !isOffline && store.canManage(current)
    var confirmingBan by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            Row(Modifier.padding(horizontal = Theme.Spacing.generous), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal)) {
                Avatar(current.name, current.avatarUrl, Theme.Size.avatarProfile, seed = current.userId)
                Column {
                    Text(current.name, style = MaterialTheme.typography.titleLarge)
                    Text(current.userId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(roleTitle(current.role)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (roles.isNotEmpty()) {
                SectionTitle(R.string.room_manage_role)
                Group(
                    *roles.map { role ->
                        @Composable {
                            Choice(roleTitle(role), selected = role == current.role) {
                                if (role != current.role) scope.launch { store.setRole(role, current) }
                            }
                        }
                    }.toTypedArray(),
                )
            }
            val rows = buildList<@Composable () -> Unit> {
                if (manageable && state.permissions.canRemove) {
                    add {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.room_manage_remove), color = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable { scope.launch { store.remove(current) }; onDismiss() },
                            colors = rowColors(),
                        )
                    }
                }
                if (manageable && state.permissions.canBan) {
                    add {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.room_manage_block), color = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable { confirmingBan = true },
                            colors = rowColors(),
                        )
                    }
                }
            }
            if (rows.isNotEmpty()) Group(*rows.toTypedArray())
        }
    }
    if (confirmingBan) {
        AlertDialog(
            onDismissRequest = { confirmingBan = false },
            title = { Text(stringResource(R.string.room_manage_block_question, current.name)) },
            text = { Text(stringResource(R.string.room_manage_block_help)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingBan = false
                    scope.launch { store.ban(current) }
                    onDismiss()
                }) { Text(stringResource(R.string.room_manage_block), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingBan = false }) { Text(stringResource(R.string.room_manage_cancel)) } },
        )
    }
}

/**
 * Позвать по Matrix ID: адрес добавляется в выбранные, «Позвать (N)» отправляет всех. Отказанные остаются выбранными — «ещё
 * раз» одним нажатием, а сказать, что не вышло, надо здесь, под листом: предупреждение экрана из-под него не видно.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteSheet(store: RoomDetailsStore, onDismiss: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<List<String>>(emptyList()) }
    var refused by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val already = remember(state.members) { state.members.map { it.userId }.toSet() }
    val candidate = typed.trim()
    val canAdd = wtf.iskra.core.rooms.RoomDetailsStore.isMatrixId(candidate) && candidate !in chosen && candidate !in already
    fun add() {
        if (canAdd) {
            chosen = chosen + candidate
            typed = ""
            refused = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Theme.Spacing.generous).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            Text(stringResource(R.string.room_manage_invite), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text(MATRIX_ID_EXAMPLE) },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (candidate in already) {
                Text(stringResource(R.string.room_invite_already_here), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (canAdd) {
                TextButton(onClick = ::add) { Text(stringResource(R.string.room_invite_by_address)) }
            }
            if (chosen.isNotEmpty()) {
                Text(stringResource(R.string.room_invite_chosen), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                chosen.forEach { id ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(id, modifier = Modifier.weight(1f))
                        IconButton(onClick = { chosen = chosen - id }, enabled = !busy) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.room_manage_cancel))
                        }
                    }
                }
            }
            if (refused) Text(stringResource(wtf.iskra.core.R.string.room_action_invite_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            PrimaryActionButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val rejected = store.invite(chosen)
                        busy = false
                        if (rejected.isEmpty()) {
                            onDismiss()
                        } else {
                            chosen = rejected
                            refused = true
                        }
                    }
                },
                enabled = chosen.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.room_invite_invite_count, chosen.size)) }
        }
    }
}

/** Закрытые комнатой, для тех, кто может их вернуть: «Вернуть» спрашивает, потому что это не «впустить», а «разрешить войти». */
@Composable
internal fun BannedSection(store: RoomDetailsStore, state: RoomDetailsState, isOffline: Boolean) {
    if (!state.permissions.canBan || state.banned.isEmpty()) return
    val scope = rememberCoroutineScope()
    var unblocking by remember { mutableStateOf<RoomMemberSummary?>(null) }
    SectionTitle(R.string.room_access_banned)
    Group(
        *state.banned.map { person ->
            @Composable {
                ListItem(
                    headlineContent = { Text(person.name) },
                    supportingContent = { Text(person.userId) },
                    leadingContent = { Avatar(person.name, person.avatarUrl, Theme.Size.avatar, seed = person.userId) },
                    trailingContent = {
                        if (!isOffline) TextButton(onClick = { unblocking = person }) { Text(stringResource(R.string.room_access_unban)) }
                    },
                    colors = rowColors(),
                )
            }
        }.toTypedArray(),
    )
    Hint(R.string.room_access_banned_footer)
    unblocking?.let { person ->
        AlertDialog(
            onDismissRequest = { unblocking = null },
            title = { Text(stringResource(R.string.room_access_unban_question, person.name)) },
            text = { Text(stringResource(R.string.room_access_unban_help)) },
            confirmButton = {
                TextButton(onClick = {
                    unblocking = null
                    scope.launch { store.unban(person) }
                }) { Text(stringResource(R.string.room_access_unban)) }
            },
            dismissButton = { TextButton(onClick = { unblocking = null }) { Text(stringResource(R.string.room_manage_cancel)) } },
        )
    }
}

/** Кто читает прошлое: три ответа в закрытой комнате, четыре в открытой. */
@Composable
internal fun HistorySection(store: RoomDetailsStore, state: RoomDetailsState, isOffline: Boolean) {
    if (!state.permissions.canChangeHistory) return
    val scope = rememberCoroutineScope()
    SectionTitle(R.string.room_access_history_header)
    Group(
        *HistoryAccess.choices(state.isPublic).map { option ->
            @Composable {
                Choice(
                    title = when (option) {
                        HistoryAccess.SHARED -> R.string.room_access_history_shared
                        HistoryAccess.INVITED -> R.string.room_access_history_invited
                        HistoryAccess.JOINED -> R.string.room_access_history_joined
                        HistoryAccess.WORLD_READABLE -> R.string.room_access_history_world_readable
                    },
                    selected = option == state.history,
                ) { if (!isOffline) scope.launch { store.setHistory(option) } }
            }
        }.toTypedArray(),
    )
    Hint(R.string.room_access_history_footer)
}

/**
 * Адрес комнаты `#имя:сервер` и «в каталоге ли сервера». Имя записывается по «Готово» и когда экран уходит — как прозвище:
 * на каждую букву это был бы запрос на сервер и «адрес занят» на каждой.
 */
@Composable
internal fun AddressSection(store: RoomDetailsStore, state: RoomDetailsState, isOffline: Boolean) {
    if (!state.permissions.canChangeAddress) return
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.loadDirectory() }
    var text by remember(state.canonicalAlias) { mutableStateOf(state.addressName) }
    SectionTitle(R.string.room_access_address_header)
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        placeholder = { Text(stringResource(R.string.room_access_address_placeholder)) },
        prefix = { Text(ALIAS_SIGIL) },
        suffix = { Text(ALIAS_SEPARATOR + store.addressServer) },
        singleLine = true,
        enabled = !isOffline,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { scope.launch { store.setAddress(text) } }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
    )
    if (text != state.addressName) {
        TextButton(onClick = { scope.launch { store.setAddress(text) } }, enabled = !isOffline, modifier = Modifier.padding(horizontal = Theme.Spacing.roomy)) {
            Text(stringResource(R.string.folders_done))
        }
    }
    Hint(R.string.room_access_address_footer)
    state.isInDirectory?.let { listed ->
        Group({
            Toggle(R.string.room_access_in_directory, null, listed, { scope.launch { store.setInDirectory(it) } }, enabled = !isOffline)
        })
        Text(
            stringResource(R.string.room_access_in_directory_help, store.addressServer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close),
        )
    }
    Spacer(Modifier.height(Theme.Spacing.close))
}

@Composable
internal fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close),
    )
}

/** Синтаксис адресов, а не слова: переводить нечего. */
private const val MATRIX_ID_EXAMPLE = "@name:server"
private const val ALIAS_SIGIL = "#"
private const val ALIAS_SEPARATOR = ":"
