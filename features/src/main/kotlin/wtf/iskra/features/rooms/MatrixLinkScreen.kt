package wtf.iskra.features.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import wtf.iskra.core.rooms.Conversations
import wtf.iskra.core.rooms.ConversationFailure
import wtf.iskra.core.rooms.MatrixLink
import wtf.iskra.core.rooms.PersonProfile
import wtf.iskra.core.rooms.PublicRoom
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar
import wtf.iskra.features.support.RoomAvatar

/**
 * Куда ведёт ссылка — отсканированная, нажатая, открытая из другого приложения: страница человека или комната, в которую
 * можно шагнуть. Порт `MatrixLinkView`.
 *
 * Не прямо в комнату. QR-код — чужой выбор, куда тебя послать, а вход виден всем, кто уже там; поэтому комната
 * показывается сначала, с тем, что о ней скажет сервер, а входят нажатием. Комната, где аккаунт уже есть, просто
 * открывается.
 */
@Composable
internal fun MatrixLinkScreen(conversations: Conversations, link: MatrixLink, onOpen: (String) -> Unit, onBack: () -> Unit) {
    when (link) {
        is MatrixLink.User -> PersonLink(conversations, link.userId, onOpen, onBack)
        is MatrixLink.Room -> RoomLink(conversations, link.address, link.via, onOpen, onBack)
    }
}

@Composable
private fun PersonLink(conversations: Conversations, userId: String, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var person by remember { mutableStateOf<PersonProfile?>(null) }
    var opening by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ConversationFailure?>(null) }
    // Имя и лицо, пока грузятся, были бы догадкой; ID довольно, чтобы писать, а профиль, когда сервер даст, — настоящий.
    LaunchedEffect(userId) { person = conversations.profile(userId) }

    Screen(R.string.qr_title, onBack) {
        val shown = person
        if (shown == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalAccent.current.control) }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Theme.Spacing.generous),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
            ) {
                Avatar(shown.name, shown.avatarUrl, Theme.Size.avatarProfile, seed = shown.id)
                Text(shown.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                if (shown.displayName != null) {
                    Text(shown.id, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PrimaryActionButton(
                    onClick = {
                        opening = true
                        scope.launch {
                            failure = attempt { onOpen(conversations.openDirectChat(shown.id)) }
                            opening = false
                        }
                    },
                    enabled = !opening,
                    modifier = Modifier.fillMaxWidth().padding(top = Theme.Spacing.roomy),
                ) { if (opening) Busy() else Text(stringResource(R.string.profile_message)) }
            }
        }
    }
    FailureDialog(failure) { failure = null }
}

@Composable
private fun RoomLink(conversations: Conversations, address: String, via: List<String>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var joined by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<PublicRoom?>(null) }
    var joining by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<ConversationFailure?>(null) }
    LaunchedEffect(address) {
        joined = conversations.joinedRoom(address)
        if (joined == null) preview = conversations.roomPreview(address, via)
        loading = false
    }

    Screen(R.string.qr_title, onBack) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Theme.Spacing.generous),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            val shown = preview
            RoomAvatar(shown?.title ?: address, shown?.avatarUrl, Theme.Size.avatarProfile, seed = shown?.id ?: address)
            Text(shown?.title ?: address, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            val people = shown?.memberCount?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.room_participants, it, it) }
            Text(
                listOfNotNull(shown?.alias ?: address, people).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            shown?.topic?.takeIf { it.isNotEmpty() }?.let { topic ->
                Text(topic, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Theme.Spacing.close))
            }
            PrimaryActionButton(
                onClick = {
                    val already = joined
                    if (already != null) {
                        onOpen(already)
                    } else {
                        joining = true
                        scope.launch {
                            failure = attempt { onOpen(conversations.joinRoom(address, via)) }
                            joining = false
                        }
                    }
                },
                enabled = !loading && !joining,
                modifier = Modifier.fillMaxWidth().padding(top = Theme.Spacing.roomy),
            ) { if (joining) Busy() else Text(stringResource(if (joined == null) R.string.qr_join_room else R.string.qr_open_room)) }
            if (!loading && joined == null) {
                val note = when {
                    shown == null -> R.string.qr_room_unknown
                    !shown.isOpen -> R.string.qr_invite_only_room
                    else -> null
                }
                if (note != null) {
                    Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
    FailureDialog(failure) { failure = null }
}
