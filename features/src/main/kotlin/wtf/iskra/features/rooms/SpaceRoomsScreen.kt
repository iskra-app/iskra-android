package wtf.iskra.features.rooms

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.rooms.SpaceRoomSummary
import wtf.iskra.core.session.UserSession
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar
import wtf.iskra.features.support.AvatarShape

/**
 * Что внутри пространства: комнаты и вложенные пространства (`SpaceRoomsView`). Комната открывается, вложенное пространство — этим же
 * экраном поверх, «назад» возвращает на уровень выше. Пусто — слова, а не пустая белая страница.
 */
@Composable
internal fun SpaceRoomsScreen(session: UserSession, spaceId: String, spaceName: String, onOpenRoom: (String) -> Unit, onBack: () -> Unit) {
    val store = remember(spaceId) { session.space(spaceId) }
    val state by store.state.collectAsStateWithLifecycle()
    var child by remember(spaceId) { mutableStateOf<SpaceRoomSummary?>(null) }
    LaunchedEffect(store) { store.load() }
    BackHandler(onBack = onBack)

    val inner = child
    if (inner != null) {
        SpaceRoomsScreen(session, inner.id, inner.name, onOpenRoom, onBack = { child = null })
        return
    }
    Screen(R.string.room_space_label, onBack) {
        Box(Modifier.fillMaxSize()) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.rooms.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(Theme.Spacing.generous),
                    verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(spaceName, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.room_space_empty_title), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.room_space_empty_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn {
                    items(state.rooms, key = { it.id }) { room ->
                        ListItem(
                            headlineContent = { Text(room.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = room.topic?.takeIf { it.isNotEmpty() }?.let { topic ->
                                { Text(topic, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            },
                            leadingContent = {
                                Avatar(
                                    room.name, room.avatarUrl, Theme.Size.roomAvatar,
                                    shape = if (room.isSpace) AvatarShape.ROUNDED_SQUARE else AvatarShape.CIRCLE,
                                    seed = if (room.isSpace) null else room.id,
                                )
                            },
                            trailingContent = if (room.isSpace) {
                                { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline) }
                            } else {
                                null
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { if (room.isSpace) child = room else onOpenRoom(room.id) },
                        )
                    }
                }
            }
        }
    }
}
