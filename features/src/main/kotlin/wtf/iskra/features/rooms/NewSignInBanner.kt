package wtf.iskra.features.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.AccountDevice
import wtf.iskra.core.session.DevicesStore
import wtf.iskra.core.session.NewSignInsStore
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.settings.DeviceCard
import wtf.iskra.features.settings.PlatformTile

/**
 * «Новый вход в ваш аккаунт — это были вы?» поперёк верха списка чатов, как спрашивает Telegram. Как сеанс считается новым — см. [NewSignInsStore].
 * Порт `NewSignInBanner`.
 *
 * Два ответа, оба в одно нажатие. «Да» записывает сеанс, и баннер уходит. «Это не я» открывает карточку сеанса с её красной кнопкой, а не завершает на месте:
 * завершение может потребовать пароль аккаунта или страницу сервера, а тот, кто только что увидел чужого в аккаунте, должен увидеть, что собирается завершить.
 * Список читается при появлении и на каждом возвращении на передний план — вход случается, пока телефон в кармане.
 */
@Composable
internal fun NewSignIns(store: NewSignInsStore) {
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var denying by remember { mutableStateOf<Denial?>(null) }
    androidx.compose.runtime.LaunchedEffect(store) { store.check() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { scope.launch { store.check() } }
    val first = sessions.firstOrNull()
    if (first != null) {
        Banner(first, more = sessions.size - 1, onConfirm = { store.confirm(first.id) }, onDeny = { denying = Denial(first, store.devicesStore()) })
    }
    denying?.let { denial ->
        DeviceCard(denial.device, denial.devices, onEnded = { store.ended(denial.device.id) }, onDismiss = { denying = null })
    }
}

private class Denial(val device: AccountDevice, val devices: DevicesStore)

@Composable
private fun Banner(device: AccountDevice, more: Int, onConfirm: () -> Unit, onDeny: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal), verticalAlignment = Alignment.Top) {
            PlatformTile(device.platform, Theme.Size.avatarToolbar)
            Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
                Text(
                    stringResource(R.string.devices_new_sign_in_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                // Устройство, на чём работает и откуда — всё, что говорит строка «Устройств», на возможно меньшем числе строк.
                Text(
                    listOfNotNull(device.title, device.detail, device.lastSeenIp).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                )
                if (more > 0) {
                    Text(stringResource(R.string.devices_new_sign_in_more, more), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal)) {
            OutlinedButton(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.devices_new_sign_in_yes)) }
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.devices_new_sign_in_no), color = MaterialTheme.colorScheme.error) }
        }
    }
}
