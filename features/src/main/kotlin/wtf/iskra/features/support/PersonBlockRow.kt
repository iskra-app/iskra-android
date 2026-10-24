package wtf.iskra.features.support

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.BlockStore
import wtf.iskra.features.R
import wtf.iskra.features.settings.rowColors

/**
 * «Заблокировать» — или «Разблокировать» — внизу страницы человека, откуда бы до неё ни дошли: инфо личного чата, имя в группе,
 * отсканированная ссылка. Одна строка, чтобы экраны, что по сути один, не разошлись в самом важном действии на них. Порт `PersonBlockButton`.
 *
 * Это блокировка *аккаунта*, `ignore` Matrix ([BlockStore]): идёт за аккаунтом на все устройства и перестаёт доставлять сообщения
 * этого человека куда бы то ни было. Не бан комнаты — тот принадлежит тому, кто ведёт группу.
 */
@Composable
internal fun PersonBlockRow(blocked: BlockStore, userId: String, name: String, enabled: Boolean = true) {
    if (userId == LocalOwnUserId.current) return
    val state by blocked.state.collectAsStateWithLifecycle()
    LaunchedEffect(blocked) { blocked.start() }
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    val isBlocked = userId in state.blockedUserIds
    ListItem(
        headlineContent = { Text(stringResource(if (isBlocked) R.string.profile_unblock_user else R.string.profile_block_user)) },
        leadingContent = { Icon(Icons.Outlined.PanTool, contentDescription = null) },
        modifier = Modifier.clickable(enabled = enabled) { if (isBlocked) scope.launch { blocked.unblock(userId) } else confirming = true },
        colors = if (isBlocked) {
            rowColors()
        } else {
            ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            )
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.message_menu_block_confirm, name)) },
            text = { Text(stringResource(R.string.message_menu_block_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch { blocked.block(userId) }
                }) { Text(stringResource(R.string.profile_block_user), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.room_manage_cancel)) } },
        )
    }
}
