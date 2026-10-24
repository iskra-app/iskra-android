package wtf.iskra.features.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import wtf.iskra.core.timeline.MessageReaction
import wtf.iskra.core.timeline.Reactor
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar

/**
 * «Кто отреагировал» (`ReactorsSheet`): вкладки «Все» и по эмодзи, под ними люди. [startKey] — эмодзи, на пилюле которого держали палец.
 * Имена приходят позже самой реакции: до них — кружок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactorsSheet(reactions: List<MessageReaction>, startKey: String?, load: suspend () -> List<Reactor>, onDismiss: () -> Unit) {
    var reactors by remember { mutableStateOf<List<Reactor>?>(null) }
    var filter by remember { mutableStateOf(startKey) }
    LaunchedEffect(Unit) { reactors = load() }
    val total = reactions.sumOf { it.count }
    val everybody = reactors?.map { it.userId }?.toSet()?.size ?: total
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            pluralStringResource(R.plurals.message_menu_reactors, total, total),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Theme.Spacing.roomy),
        )
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            item {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.message_menu_reactors_all, everybody)) })
            }
            items(reactions, key = { it.key }) { reaction ->
                FilterChip(selected = filter == reaction.key, onClick = { filter = reaction.key }, label = { Text(stringResource(R.string.room_reaction_pill, reaction.key, reaction.count)) })
            }
        }
        val shown = reactors?.filter { filter == null || it.key == filter }
        if (shown == null) {
            Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn {
                items(shown, key = { "${it.key}|${it.userId}" }) { person ->
                    ListItem(
                        headlineContent = { Text(person.name, maxLines = 1) },
                        leadingContent = { Avatar(person.name, person.avatarUrl, Theme.Size.avatar, seed = person.userId) },
                        trailingContent = { Text(person.key, style = MaterialTheme.typography.titleLarge) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
