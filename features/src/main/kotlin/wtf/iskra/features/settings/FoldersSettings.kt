package wtf.iskra.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.rooms.ChatFolder
import wtf.iskra.core.rooms.ChatFolders
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.core.session.UserSession
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Папки чатов: список, предложенные для начала и редактор правила (`FoldersView.swift`). Папка — фильтр над
 * списком чатов, не копия: что в неё входит, говорит правило, а не человек по одному.
 */
@Composable
internal fun FoldersPage(session: UserSession) {
    val library = session.folders
    val state by library.state.collectAsStateWithLifecycle()
    val rooms by session.rooms.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(library) { library.load() }
    // null — редактор закрыт; пустая новая папка — «Новая папка».
    var editing by remember { mutableStateOf<ChatFolder?>(null) }
    var isNew by remember { mutableStateOf(false) }

    Text(
        stringResource(R.string.folders_explanation),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.generous),
    )
    val folders = state.folders
    if (folders.isNotEmpty()) {
        Group(*folders.mapIndexed { index, folder ->
            @Composable {
                ListItem(
                    headlineContent = { Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(kindsSummary(folder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { scope.launch { library.move(folder.id, -1) } }, enabled = index > 0) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = null)
                            }
                            IconButton(onClick = { scope.launch { library.move(folder.id, 1) } }, enabled = index < folders.lastIndex) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = null)
                            }
                        }
                    },
                    modifier = Modifier.clickable { editing = folder; isNew = false },
                    colors = rowColors(),
                )
            }
        }.toTypedArray())
    }
    if (folders.size < ChatFolders.LIMIT) {
        Group({
            ListItem(
                headlineContent = { Text(stringResource(R.string.folders_new)) },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null, tint = wtf.iskra.design.LocalAccent.current.control) },
                modifier = Modifier.clickable { editing = ChatFolder(name = ""); isNew = true },
                colors = rowColors(),
            )
        })
        // С чего начать: то, чего ещё нет по имени. Добавляется сразу и правится потом.
        val suggestions = ChatFolder.Companion.Suggestion.entries.map { which ->
            which to stringResource(
                when (which) {
                    ChatFolder.Companion.Suggestion.PERSONAL -> R.string.folders_kind_direct
                    ChatFolder.Companion.Suggestion.GROUPS -> R.string.folders_kind_groups
                    ChatFolder.Companion.Suggestion.UNREAD -> R.string.folders_suggestion_unread
                },
            )
        }.filter { (_, name) -> folders.none { it.name == name } }
        if (state.hasLoaded && suggestions.isNotEmpty()) {
            SectionTitle(R.string.folders_suggested)
            Group(*suggestions.map { (which, name) ->
                @Composable {
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = {
                            TextButton(onClick = { scope.launch { library.save(ChatFolder.suggestion(which, name)) } }) {
                                Text(stringResource(R.string.folders_add))
                            }
                        },
                        colors = rowColors(),
                    )
                }
            }.toTypedArray())
        }
    }

    editing?.let { folder ->
        FolderEditor(
            folder = folder,
            isNew = isNew,
            rooms = rooms.rooms + rooms.archivedRooms,
            onSave = { saved ->
                editing = null
                scope.launch { library.save(saved) }
            },
            onDelete = {
                editing = null
                scope.launch { library.delete(folder.id) }
            },
            onDismiss = { editing = null },
        )
    }
    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = library::clearFailure,
            confirmButton = { TextButton(onClick = library::clearFailure) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(failure.explanation)) },
        )
    }
}

@Composable
private fun kindsSummary(folder: ChatFolder): String {
    // Слова — до `joinToString`: он не inline, и `stringResource` в его лямбде не композируется.
    val titles = ChatFolder.Kind.entries.associateWith { stringResource(kindTitle(it)) }
    return folder.kinds.sortedBy { it.ordinal }.joinToString(", ") { titles.getValue(it) }
}

private fun kindTitle(kind: ChatFolder.Kind): Int = when (kind) {
    ChatFolder.Kind.DIRECT -> R.string.folders_kind_direct
    ChatFolder.Kind.GROUPS -> R.string.folders_kind_groups
    ChatFolder.Kind.SPACES -> R.string.folders_kind_spaces
    ChatFolder.Kind.INVITATIONS -> R.string.folders_kind_invitations
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderEditor(
    folder: ChatFolder,
    isNew: Boolean,
    rooms: List<RoomSummary>,
    onSave: (ChatFolder) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(folder) }
    var choosing by remember { mutableStateOf<Choosing?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val names = remember(rooms) { rooms.associate { it.id to it.name } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Theme.Spacing.roomy),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            Text(
                stringResource(if (isNew) R.string.folders_new else R.string.folders_edit),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text(stringResource(R.string.folders_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle(R.string.folders_kinds_header)
            ChatFolder.Kind.entries.forEach { kind ->
                CheckRow(stringResource(kindTitle(kind)), kind in draft.kinds) { on ->
                    draft = draft.copy(kinds = if (on) draft.kinds + kind else draft.kinds - kind)
                }
            }

            SectionTitle(R.string.folders_conditions_header)
            SwitchRow(stringResource(R.string.folders_only_unread), draft.onlyUnread) { draft = draft.copy(onlyUnread = it) }
            SwitchRow(stringResource(R.string.folders_excludes_muted), draft.excludesMuted) { draft = draft.copy(excludesMuted = it) }
            SwitchRow(stringResource(R.string.folders_includes_archived), draft.includesArchived) { draft = draft.copy(includesArchived = it) }

            ChatList(R.string.folders_included_header, draft.included, names, onRemove = { id -> draft = draft.copy(included = draft.included - id) }) {
                choosing = Choosing.INCLUDED
            }
            ChatList(R.string.folders_excluded_header, draft.excluded, names, onRemove = { id -> draft = draft.copy(excluded = draft.excluded - id) }) {
                choosing = Choosing.EXCLUDED
            }

            PrimaryActionButton(onClick = { onSave(draft.copy(name = draft.name.trim())) }, enabled = draft.isMeaningful, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.folders_done))
            }
            Row(Modifier.fillMaxWidth().padding(bottom = Theme.Spacing.generous), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) }
                if (!isNew) {
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(stringResource(R.string.folders_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    choosing?.let { which ->
        val taken = if (which == Choosing.INCLUDED) draft.included else draft.excluded
        ChatChooser(rooms, taken, onDismiss = { choosing = null }) { picked ->
            choosing = null
            // Один чат не в обоих списках сразу: то, что говорит последнее слово, — единственное.
            draft = if (which == Choosing.INCLUDED) {
                draft.copy(included = picked, excluded = draft.excluded - picked.toSet())
            } else {
                draft.copy(excluded = picked, included = draft.included - picked.toSet())
            }
        }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.folders_delete_question, folder.name)) },
            text = { Text(stringResource(R.string.folders_delete_help)) },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(stringResource(R.string.folders_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.folders_cancel)) } },
        )
    }
}

private enum class Choosing { INCLUDED, EXCLUDED }

@Composable
private fun CheckRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(Theme.Spacing.close))
        Text(title)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = Theme.Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Чаты, которые всегда внутри или всегда снаружи, — по именам, с крестиком и «Добавить чаты». */
@Composable
private fun ChatList(header: Int, ids: List<String>, names: Map<String, String>, onRemove: (String) -> Unit, onAdd: () -> Unit) {
    SectionTitle(header)
    ids.forEach { id ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(names[id] ?: id, modifier = Modifier.weight(1f).padding(start = Theme.Spacing.close), maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = { onRemove(id) }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.folders_cancel)) }
        }
    }
    TextButton(onClick = onAdd) { Text(stringResource(R.string.folders_add_chats)) }
}

/** Выбор чатов отметками; [taken] — уже выбранные. Готово отдаёт весь итоговый список. */
@Composable
private fun ChatChooser(rooms: List<RoomSummary>, taken: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(taken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_choose_chats)) },
        text = {
            LazyColumn(Modifier.heightIn(max = Theme.Size.chatChooser)) {
                items(rooms, key = { it.id }) { room ->
                    CheckRow(room.name, room.id in picked) { on -> picked = if (on) picked + room.id else picked - room.id }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked) }) { Text(stringResource(R.string.folders_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.folders_cancel)) } },
    )
}
