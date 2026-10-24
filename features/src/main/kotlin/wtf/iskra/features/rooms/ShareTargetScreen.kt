package wtf.iskra.features.rooms

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.RoomAvatar

/**
 * Куда отправить то, чем поделились из другого приложения: список чатов с поиском. Выбранный чат открывается с этим
 * содержимым в поле ввода и под миниатюрами — отправляет человек, одним нажатием, после того как увидел, что уходит.
 */
@Composable
internal fun ShareTargetScreen(rooms: List<RoomSummary>, onPick: (String) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = rooms.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    Screen(wtf.iskra.core.R.string.share_title, onBack) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.message_forward_prompt)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.generous, vertical = Theme.Spacing.close),
            )
            LazyColumn {
                items(shown, key = { it.id }) { room ->
                    ListItem(
                        headlineContent = { Text(room.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { RoomAvatar(room.name, room.avatarUrl, Theme.Size.avatar, seed = room.avatarSeed) },
                        modifier = Modifier.clickable { onPick(room.id) },
                    )
                }
            }
        }
    }
}
