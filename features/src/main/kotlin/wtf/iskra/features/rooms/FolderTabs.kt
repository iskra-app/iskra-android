package wtf.iskra.features.rooms

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import wtf.iskra.core.rooms.ChatFolder
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Папки вкладками над списком — «Все» первой, затем каждая со счётом чатов, где есть непрочитанное. Полоса
 * Telegram. Порт `FolderTabs.swift`.
 *
 * Ничего, когда папок нет: полоса с одной вкладкой — кнопка, которая ничего не делает. Долгое нажатие на
 * полосу открывает настройки папок.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderTabs(
    folders: List<ChatFolder>,
    rooms: List<RoomSummary>,
    archived: List<RoomSummary>,
    selection: String,
    onSelect: (String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onEdit)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab(stringResource(R.string.folders_all), rooms.count { it.hasUnread }, selection == ALL) { onSelect(ALL) }
        folders.forEach { folder ->
            Tab(folder.name, folder.rooms(rooms, archived).count { it.hasUnread }, selection == folder.id) { onSelect(folder.id) }
        }
    }
}

@Composable
private fun Tab(title: String, unread: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .combinedClickable(onClick = onClick, role = Role.Tab)
            .semantics { this.selected = selected }
            .padding(horizontal = Theme.Spacing.normal, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unread > 0) {
            Text(
                unread.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .padding(horizontal = Theme.Spacing.tight),
            )
        }
    }
}

/** Идентификатор вкладки «Все»: пустая строка, как на iOS. */
internal const val ALL = ""
