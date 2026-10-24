package wtf.iskra.features.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import wtf.iskra.core.timeline.Reader
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Кто отреагировал и кто прочитал» (`ReactorsSheet`): вкладки «Все», по эмодзи и «прочитали», под ними люди — лицо, имя, когда, и
 * эмодзи, который оставили, либо две галочки у того, кто прочёл и ничего не оставил. «Все» — все, кто что-то сделал с
 * сообщением, по разу каждый, как в Telegram. [startKey] — эмодзи, на пилюле которого держали палец. Имена приходят позже
 * самой реакции: до них — кружок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReactorsSheet(
    reactions: List<MessageReaction>,
    startKey: String?,
    load: suspend () -> List<Reactor>,
    onDismiss: () -> Unit,
    /** Кто прочёл. Null — спросить некого (личный чат, тред). */
    loadReaders: (suspend () -> List<Reader>)? = null,
) {
    var reactors by remember { mutableStateOf<List<Reactor>?>(null) }
    var readers by remember { mutableStateOf<List<Reader>>(emptyList()) }
    var filter by remember { mutableStateOf(startKey) }
    LaunchedEffect(Unit) {
        reactors = load()
        readers = loadReaders?.invoke().orEmpty()
    }
    val total = reactions.sumOf { it.count }
    val everybody = reactors?.let { people -> (people.map { it.userId } + readers.map { it.userId }).toSet().size } ?: total
    val lines = remember(reactors, readers, filter) { lines(reactors.orEmpty(), readers, filter) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Text(
            if (total > 0) pluralStringResource(R.plurals.message_menu_reactors, total, total)
            else pluralStringResource(R.plurals.message_menu_seen, readers.size, readers.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Theme.Spacing.roomy),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            item {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text(stringResource(R.string.message_menu_reactors_all, everybody)) })
            }
            items(reactions, key = { it.key }) { reaction ->
                FilterChip(selected = filter == reaction.key, onClick = { filter = reaction.key }, label = { Text(stringResource(R.string.room_reaction_pill, reaction.key, reaction.count)) })
            }
            if (readers.isNotEmpty()) {
                item {
                    FilterChip(
                        selected = filter == READ_FILTER,
                        onClick = { filter = READ_FILTER },
                        label = { Text(readers.size.toString()) },
                        leadingIcon = { Icon(Icons.Filled.DoneAll, contentDescription = pluralStringResource(R.plurals.message_menu_seen, readers.size, readers.size), modifier = Modifier.size(Theme.Size.menuIcon)) },
                    )
                }
            }
        }
        if (reactors == null) {
            Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn {
                items(lines, key = { it.id }) { line ->
                    ListItem(
                        headlineContent = { Text(line.name, maxLines = 1) },
                        supportingContent = line.date?.let { date -> { Text(DATE.format(date.atZone(ZoneId.systemDefault()))) } },
                        leadingContent = { Avatar(line.name, line.avatarUrl, Theme.Size.avatar, seed = line.userId) },
                        trailingContent = {
                            if (line.key != null) Text(line.key, style = MaterialTheme.typography.titleLarge)
                            else Icon(Icons.Filled.DoneAll, contentDescription = pluralStringResource(R.plurals.message_menu_seen, 1, 1), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}

/** Строка списка: реакция или человек, что прочёл и ничего не оставил. */
internal data class ReactorLine(val id: String, val userId: String, val name: String, val avatarUrl: String?, val key: String?, val date: Instant?)

/** «Все» — оставившие реакцию, потом прочитавшие без неё; «прочитали» — только вторые; эмодзи — его люди. */
internal fun lines(reactors: List<Reactor>, readers: List<Reader>, filter: String?): List<ReactorLine> {
    val reacted = reactors.map { ReactorLine("${it.key}|${it.userId}", it.userId, it.name, it.avatarUrl, it.key, it.reactedAt) }
    val reactedIds = reactors.map { it.userId }.toSet()
    val read = readers.map { ReactorLine("read|${it.userId}", it.userId, it.name, it.avatarUrl, null, it.readAt) }
    return when (filter) {
        null -> reacted + read.filter { it.userId !in reactedIds }
        READ_FILTER -> read
        else -> reacted.filter { it.key == filter }
    }
}

/** Не эмодзи, который можно оставить, — вкладка тех, кто только прочёл. */
private const val READ_FILTER = "\u0000read"

private val DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
