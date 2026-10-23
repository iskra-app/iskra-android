package wtf.iskra.features.room

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.rooms.RoomDetailsStore
import wtf.iskra.core.rooms.RoomMemberSummary
import wtf.iskra.core.rooms.RoomRule
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.settings.Group
import wtf.iskra.features.settings.SectionTitle
import wtf.iskra.features.settings.rowColors
import wtf.iskra.features.support.Avatar

private fun ruleTitle(rule: RoomRule): Int = when (rule) {
    RoomRule.SEND_MESSAGES -> R.string.room_rules_send_messages
    RoomRule.INVITE -> R.string.room_rules_invite
    RoomRule.REMOVE -> R.string.room_rules_remove
    RoomRule.BAN -> R.string.room_rules_ban
    RoomRule.DELETE_OTHERS -> R.string.room_rules_delete_others
    RoomRule.RENAME -> R.string.room_rules_rename
    RoomRule.CHANGE_TOPIC -> R.string.room_rules_change_topic
    RoomRule.CHANGE_PICTURE -> R.string.room_rules_change_picture
}

/** Кто это может: «все» для самой низкой ступени, дальше всё уже. */
private fun choiceTitle(role: RoomMemberSummary.Role): Int = when (role) {
    RoomMemberSummary.Role.MEMBER -> R.string.room_rules_everyone
    RoomMemberSummary.Role.MODERATOR -> R.string.room_rules_moderators
    RoomMemberSummary.Role.ADMINISTRATOR -> R.string.room_rules_administrators
}

/**
 * Кто ведёт комнату и какой ступени что можно — «Роли и права», из правки чата для тех, кто может менять уровни комнаты.
 * Порт `RoomRulesView`.
 *
 * Два списка. Люди с ролью — теми же строками, что участники: нажатие открывает карточку (там и роль, и «убрать роль»), а
 * «Добавить» под ними назначает кого-то из остальных. И правила комнаты — каждое выбором самой низкой ступени, которой
 * можно: все, модераторы, администраторы. Правило, что этому аккаунту не по плечу (стоит выше его ступени), показано как
 * факт, а не как серый выбор.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun RoomRulesScreen(store: RoomDetailsStore, isOffline: Boolean, onBack: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    var picked by remember { mutableStateOf<RoomMemberSummary?>(null) }
    var appointing by remember { mutableStateOf(false) }
    val staff = state.members.filter { it.role != RoomMemberSummary.Role.MEMBER }
    // Назначить можно того, кто в комнате, ниже тебя и без роли.
    val candidates = state.members.filter { it.role == RoomMemberSummary.Role.MEMBER && appointable(store, it).isNotEmpty() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text(stringResource(R.string.room_rules_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back)) }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            SectionTitle(R.string.room_rules_staff_header)
            val staffRows = buildList<@Composable () -> Unit> {
                staff.forEach { member ->
                    add {
                        ListItem(
                            headlineContent = { Text(member.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(stringResource(roleTitle(member.role))) },
                            leadingContent = { Avatar(member.name, member.avatarUrl, Theme.Size.avatar, seed = member.userId) },
                            modifier = Modifier.clickable { picked = member },
                            colors = rowColors(),
                        )
                    }
                }
                if (state.permissions.canChangeRoles && !isOffline) {
                    add {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.room_rules_add_staff)) },
                            leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, tint = LocalAccent.current.control) },
                            modifier = Modifier.clickable { appointing = true },
                            colors = rowColors(),
                        )
                    }
                }
            }
            if (staffRows.isNotEmpty()) Group(*staffRows.toTypedArray())
            Hint(R.string.room_rules_staff_footer)

            if (state.rules != null) {
                SectionTitle(R.string.room_rules_rules_header)
                Group(
                    *RoomRule.entries.map { rule ->
                        // Роль и ступени — параметрами: строка читает стор мимо снимка, и без этого Compose не видит, что она устарела.
                        @Composable {
                            RuleRow(rule, store.roleFor(rule), if (isOffline) emptyList() else store.rolesFor(rule)) { role ->
                                scope.launch { store.setRule(rule, role) }
                            }
                        }
                    }.toTypedArray(),
                )
                Hint(R.string.room_rules_rules_footer)
            }
        }
    }

    picked?.let { member -> MemberSheet(store, member, isOffline, onDismiss = { picked = null }) }
    if (appointing) {
        AppointDialog(
            candidates = candidates,
            rolesFor = { appointable(store, it) },
            onAppoint = { member, role -> scope.launch { store.setRole(role, member) } },
            onDismiss = { appointing = false },
        )
    }
}

/** Роли выше «участника», которые можно отдать этому человеку. */
private fun appointable(store: RoomDetailsStore, member: RoomMemberSummary): List<RoomMemberSummary.Role> =
    store.assignableRoles(member).filter { it != RoomMemberSummary.Role.MEMBER }

/** Правило: выбор ступени, если их больше одной по плечу, иначе — просто сказанное. */
@Composable
private fun RuleRow(
    rule: RoomRule,
    current: RoomMemberSummary.Role,
    choices: List<RoomMemberSummary.Role>,
    onPick: (RoomMemberSummary.Role) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(ruleTitle(rule))) },
        trailingContent = {
            if (choices.size > 1) {
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { open = true }) { Text(stringResource(choiceTitle(current))) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        // Низшая ступень первой: «все» наверху, самое узкое — внизу.
                        choices.reversed().forEach { role ->
                            DropdownMenuItem(text = { Text(stringResource(choiceTitle(role))) }, onClick = { open = false; onPick(role) })
                        }
                    }
                }
            } else {
                Text(stringResource(choiceTitle(current)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        colors = rowColors(),
    )
}

/** Выбрать из комнаты того, с кем её вести, и — по нажатию — роль, которую ему дать. */
@Composable
private fun AppointDialog(
    candidates: List<RoomMemberSummary>,
    rolesFor: (RoomMemberSummary) -> List<RoomMemberSummary.Role>,
    onAppoint: (RoomMemberSummary, RoomMemberSummary.Role) -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember { mutableStateOf<RoomMemberSummary?>(null) }
    val person = chosen
    if (person != null) {
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(stringResource(R.string.room_manage_choose_role)) },
            text = {
                Column {
                    Text(person.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rolesFor(person).forEach { role ->
                        TextButton(onClick = { onAppoint(person, role); onDismiss() }) { Text(stringResource(roleTitle(role))) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.room_manage_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.room_rules_appoint_title)) },
        text = {
            if (candidates.isEmpty()) {
                Text(stringResource(R.string.room_rules_nobody_to_appoint))
            } else {
                LazyColumn {
                    items(candidates, key = { it.userId }) { member ->
                        Row(Modifier.clickable { chosen = member }.padding(vertical = Theme.Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(member.name, member.avatarUrl, Theme.Size.avatar, seed = member.userId)
                            Text(member.name, modifier = Modifier.padding(start = Theme.Spacing.normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.room_manage_cancel)) } },
    )
}
