package wtf.iskra.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppSettings
import wtf.iskra.features.R
import wtf.iskra.design.Theme

/**
 * «Конфиденциальность»: что о вас узнают без вашего слова — присутствие и набор — и кого вы не хотите видеть. Переключатели
 * применяются сразу: «скрыть, когда я был здесь», что вступит в силу в следующий раз, — переключатель, что сегодня не сработал.
 */
@Composable
internal fun PrivacyPage(settings: AppSettings, onBlocked: () -> Unit) {
    val privacy by settings.privacy.collectAsStateWithLifecycle()
    Group(
        { Toggle(R.string.privacy_hide_presence, R.string.privacy_hide_presence_help, privacy.hidesPresence, { settings.setPrivacy(privacy.copy(hidesPresence = it)) }) },
        { Toggle(R.string.privacy_hide_typing, R.string.privacy_hide_typing_help, privacy.hidesTyping, { settings.setPrivacy(privacy.copy(hidesTyping = it)) }) },
    )
    Group({
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_blocked)) },
            leadingContent = { Icon(Icons.Outlined.PanTool, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onBlocked),
            colors = rowColors(),
        )
    })
}

/**
 * Те, кого вы заблокировали, и путь назад. Только Matrix ID, не имена: список, что отдаёт SDK, — список ID, а имя на каждую строку
 * значило бы запрос на экран, который редко открывают. ID однозначен и так: чьё угодно имя может быть чьим угодно.
 */
@Composable
internal fun BlockedPage(session: UserSession) {
    val store = session.blocked
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.start() }
    if (state.blockedUserIds.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(Theme.Spacing.generous),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            Icon(Icons.Outlined.PanTool, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_blocked_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.settings_blocked_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Group(
            *state.blockedUserIds.map { userId ->
                @Composable {
                    ListItem(
                        headlineContent = { Text(userId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            TextButton(onClick = { scope.launch { store.unblock(userId) } }) { Text(stringResource(R.string.settings_unblock)) }
                        },
                        colors = rowColors(),
                    )
                }
            }.toTypedArray(),
        )
    }
    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = store::failureShown,
            text = { Text(stringResource(failure.message)) },
            confirmButton = { TextButton(onClick = store::failureShown) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}
