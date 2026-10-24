package wtf.iskra.features.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import wtf.iskra.core.transport.BridgeCheck
import wtf.iskra.core.transport.TunnelSettings
import wtf.iskra.core.transport.TunnelStatus
import wtf.iskra.design.SecondaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.Words
import wtf.iskra.features.support.words

/**
 * Что экрану «Подключение» нужно от корня: настройки моста, что транспорт делает сейчас, ключ устройства
 * и проверка моста. Собирает `RootModel.connection()`.
 */
class ConnectionHooks(
    val settings: TunnelSettings,
    val status: () -> TunnelStatus,
    val deviceKey: () -> String?,
    /** [BridgeCheck.run] — та же функция, что у пробы `--es probe bridge`. */
    val check: suspend (bridgeKeyHex: String, relays: List<String>, homeserver: String) -> BridgeCheck.Outcome,
)

/**
 * Где трафик идёт через мост, а не прямо к серверу. Порт `ConnectionView.swift`.
 *
 * Изменение здесь вступает в силу, когда с экрана уходят: сеанс закрывается и восстанавливается снова
 * (`TunnelSettings.changed` → корень). Не на каждое нажатие клавиши — сеанс, пересобранный на
 * полувставленном ключе, пересобран зря, — и не *никогда*: клиент построен вокруг адреса и на лету не
 * перенаправляется, а «со следующего запуска» значило бы приложение, которое перестаёт грузить
 * сообщения, едва человек включил то, что их починит.
 *
 * Статус показывает, что происходит **сейчас**, а не что выбрано; пока изменение не применено, экран
 * говорит, что ждёт, и предлагает применить сразу. Тексты о relay'ях, токене и hex автор оставил как
 * есть — их не «упрощать» (docs/PROMPT.md, 9.8).
 */
@Composable
internal fun ConnectionPage(hooks: ConnectionHooks, homeserver: String) {
    val settings = hooks.settings
    val enabled by settings.enabled.collectAsStateWithLifecycle()
    val address by settings.address.collectAsStateWithLifecycle()
    val relays by settings.relaysText.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var status by remember { mutableStateOf(hooks.status()) }
    val deviceKey = remember { hooks.deviceKey() }
    var copied by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<BridgeCheck.Outcome?>(null) }

    // С чем был построен сеанс, когда экран открылся. Разница на выходе — просьба пересобрать сеанс.
    var appeared by remember { mutableStateOf(settings.snapshot()) }
    val pending = remember(enabled, address, relays, appeared) { settings.snapshot() != appeared }
    val apply = {
        appeared = settings.snapshot()
        settings.announceChange()
    }
    val stillPending by rememberUpdatedState(pending)
    DisposableEffect(Unit) {
        onDispose { if (stillPending) settings.announceChange() }
    }

    // Relay'и подключаются и отваливаются сами, и мост отвечает не сразу: статус спрашивается снова, пока экран открыт.
    LaunchedEffect(hooks) {
        while (true) {
            status = hooks.status()
            delay(STATUS_REFRESH_MILLIS)
        }
    }

    Group({
        Toggle(R.string.connection_bridge, R.string.connection_bridge_footer, enabled, onChange = settings::setEnabled)
    })

    SectionTitle(R.string.connection_address)
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        OutlinedTextField(
            value = address,
            onValueChange = settings::setAddress,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.connection_address_prompt)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
            maxLines = 3,
        )
        // Пустое поле — не ошибка: с него начинают все.
        val complaint = when (TunnelSettings.addressProblem(address)) {
            TunnelSettings.AddressProblem.LOOKS_LIKE_A_SECRET_KEY -> R.string.connection_address_secret
            TunnelSettings.AddressProblem.NOT_A_KEY -> R.string.connection_address_bad
            null -> null
        }
        complaint?.let {
            ListItem(
                headlineContent = { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) },
                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                colors = rowColors(),
            )
        }
        if (address.trim() != TunnelSettings.DEFAULT_ADDRESS) {
            TextButton(onClick = { settings.setAddress(TunnelSettings.DEFAULT_ADDRESS) }) {
                Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = null)
                Text(stringResource(R.string.connection_default_bridge), modifier = Modifier.padding(start = Theme.Spacing.close))
            }
        }
        Footnote(R.string.connection_address_footer)
    }

    SectionTitle(R.string.connection_relays)
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        OutlinedTextField(
            value = relays,
            onValueChange = settings::setRelays,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.connection_relays_prompt)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            maxLines = 4,
        )
        Footnote(R.string.connection_relays_footer)
    }

    if (deviceKey != null) {
        SectionTitle(R.string.connection_my_key)
        Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            Text(deviceKey, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(null, deviceKey))
                copied = true
                scope.launch {
                    delay(COPIED_MILLIS)
                    copied = false
                }
            }) {
                Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, contentDescription = null)
                Text(
                    stringResource(if (copied) R.string.connection_copied else R.string.connection_copy),
                    modifier = Modifier.padding(start = Theme.Spacing.close),
                )
            }
            Footnote(R.string.connection_my_key_footer)
        }
    }

    SectionTitle(R.string.connection_status)
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        Text(status.words().resolve(), style = MaterialTheme.typography.bodyLarge)
        if (pending) {
            Footnote(R.string.connection_pending)
            SecondaryActionButton(onClick = apply, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.connection_apply_now))
            }
        }
        val bridgeKey = TunnelSettings.bridgeKeyOf(address)
        SecondaryActionButton(
            onClick = {
                val key = bridgeKey ?: return@SecondaryActionButton
                checking = true
                outcome = null
                scope.launch {
                    outcome = hooks.check(key, TunnelSettings.parseRelays(relays).ifEmpty { TunnelSettings.DEFAULT_RELAYS }, homeserver)
                    checking = false
                }
            },
            enabled = !checking && bridgeKey != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (checking) R.string.connection_checking else R.string.connection_check))
        }
        outcome?.let { Text(it.words().resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }

    // Обе секции — смысл экрана, а не украшение вокруг него.
    SectionTitle(R.string.connection_unfinished)
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy)) { Footnote(R.string.connection_unfinished_body) }
    SectionTitle(R.string.connection_sees)
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), horizontalAlignment = Alignment.Start) {
        Footnote(R.string.connection_sees_body)
    }
}

@Composable
private fun Footnote(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Статус словами: что верно о трафике сейчас. «Не видит ваших сообщений» — только [TunnelStatus.BridgedBlind]. */
private fun TunnelStatus.words(): Words = when (this) {
    TunnelStatus.Off -> Words(R.string.connection_status_direct)
    is TunnelStatus.Connecting -> Words(R.string.connection_status_connecting, relays)
    is TunnelStatus.BridgedBlind -> Words(R.string.connection_status_blind, relays)
    is TunnelStatus.Refused -> refusal.words()
    is TunnelStatus.Failed -> Words(R.string.connection_status_failed)
}

private fun BridgeCheck.Outcome.words(): Words = when (this) {
    is BridgeCheck.Outcome.Reachable -> Words(R.string.connection_check_ok, milliseconds)
    is BridgeCheck.Outcome.Refused -> refusal.words()
    BridgeCheck.Outcome.NoRelay -> Words(R.string.connection_check_no_relay)
    BridgeCheck.Outcome.Silent -> Words(R.string.connection_check_silent)
    is BridgeCheck.Outcome.Failed -> Words(R.string.connection_check_failed, reason)
}

private const val STATUS_REFRESH_MILLIS = 2_000L
private const val COPIED_MILLIS = 2_000L
