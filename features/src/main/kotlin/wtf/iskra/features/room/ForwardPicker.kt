package wtf.iskra.features.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import wtf.iskra.core.timeline.Message
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar

/**
 * «Переслать…»: чаты с поиском, галочкой отмечаются несколько, внизу — слова, что уйдут следом, и «Отправить». Порт
 * `ForwardPicker`: сначала то, что передаётся, потом то, что вы скажете о нём. Полэкрана сразу — большинство пересылок идёт
 * кому-то из верхних строк.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForwardPicker(messages: List<Message>, forwarding: Forwarding, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateListOf<String>() }
    val rooms = remember(query) {
        val all = forwarding.destinations()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) all else all.filter { it.name.contains(trimmed, ignoreCase = true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.message_forward_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.message_forward_cancel)) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.message_forward_prompt)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
            )
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = Theme.Size.listSheetMaxHeight)) {
                items(rooms, key = { it.id }) { room ->
                    val selected = room.id in chosen
                    ListItem(
                        headlineContent = { Text(room.name, maxLines = 1) },
                        leadingContent = { Avatar(room.name, room.avatarUrl, Theme.Size.avatar, seed = room.avatarSeed) },
                        trailingContent = {
                            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = LocalAccent.current.control)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { if (selected) chosen.remove(room.id) else chosen.add(room.id) },
                    )
                }
            }
            if (chosen.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
                    horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        placeholder = { Text(stringResource(R.string.message_forward_comment)) },
                        keyboardOptions = KeyboardOptions.Default,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !sending,
                        onClick = {
                            sending = true
                            val targets = chosen.toList()
                            val words = comment.trim()
                            scope.launch {
                                for (roomId in targets) {
                                    for (message in messages) forwarding.forward(message, roomId)
                                    if (words.isNotEmpty()) forwarding.comment(words, roomId)
                                }
                                onDismiss()
                            }
                        },
                    ) { Text(stringResource(R.string.message_forward_send) + " (${chosen.size})") }
                }
            }
        }
    }
}

/** «Пожаловаться»: слова про то, что не так, необязательные, и «Отправить». Порт `ReportSheet`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReportSheet(onReport: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            Text(stringResource(R.string.message_report_title), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = { Text(stringResource(R.string.message_report_reason_prompt)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.message_report_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(bottom = Theme.Spacing.roomy), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.message_report_cancel)) }
                TextButton(onClick = { onReport(reason.trim()); onDismiss() }) { Text(stringResource(R.string.message_report_submit)) }
            }
        }
    }
}
