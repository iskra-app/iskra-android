package wtf.iskra.features.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.PinnedStore
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.rooms.RoomTime
import wtf.iskra.features.support.Avatar
import java.time.Instant
import java.time.ZoneId

/**
 * Всё закреплённое в комнате, старое сверху (`PinnedMessagesView`). **Список**, не переписка: это
 * закрепили, чтобы найти снова, и здесь пробегают глазами — строки с именем и словами, а не пузыри
 * с реакциями и меню, предлагающим ответить там, где нет композера.
 *
 * Нажатие ведёт к сообщению — тот же переход, что у полосы; булавка в строке открепляет (на iOS —
 * смахиванием; здесь кнопкой: видна и доступна без жеста).
 */
@Composable
internal fun PinnedMessages(store: PinnedStore, isOneToOne: Boolean, onOpen: (String) -> Unit, onClose: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
                androidx.compose.material3.TopAppBar(
                    title = { Text(stringResource(R.string.room_pinned_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    },
                    windowInsets = WindowInsets(0),
                )
                when {
                    !state.hasLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalAccent.current.control)
                    }
                    state.messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.room_pinned_empty),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        val now = Instant.now()
                        val locale = LocalConfiguration.current.locales[0]
                        LazyColumn {
                            items(state.messages, key = { it.id }) { message ->
                                PinnedRow(
                                    message,
                                    isOneToOne = isOneToOne,
                                    time = RoomTime.label(message.timestamp, now, ZoneId.systemDefault(), locale),
                                    onOpen = { onOpen(message.id) },
                                    onUnpin = { scope.launch { store.unpin(message.id) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinnedRow(message: Message, isOneToOne: Boolean, time: String, onOpen: () -> Unit, onUnpin: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = Theme.Spacing.roomy, top = Theme.Spacing.close, bottom = Theme.Spacing.close),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        // Вдвоём различать некого — как и в самой переписке: лицо уходит, строке достаётся ширина.
        if (!isOneToOne) Avatar(message.senderName, message.senderAvatarUrl, Theme.Size.avatarSmall, seed = message.senderId)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                body(message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onUnpin) {
            Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.room_unpin), tint = LocalAccent.current.control)
        }
    }
}
