package wtf.iskra.features.settings

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material.icons.outlined.TabletMac
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.AccountDevice
import wtf.iskra.core.session.DevicePlatform
import wtf.iskra.core.session.DevicesStore
import wtf.iskra.core.session.UserSession
import wtf.iskra.design.EntryField
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * «Устройства»: это устройство вверху, красная кнопка, что закрывает все остальные, и под ней остальные сессии аккаунта — что
 * это, на чём работает, где и когда видели, — каждая в одно нажатие от карточки со всем, что знает сервер. Порт `DevicesView`.
 *
 * Список — сервера (`AccountDevices`). Завершение спрашивает сервер; где он просит пароль аккаунта — экран его спрашивает; где
 * сессии принадлежат самому серверу (вход через браузер) — «Завершить» открывает его страницу, а список перечитывается, когда
 * человек вернулся.
 */
@Composable
internal fun DevicesPage(session: UserSession) {
    val store = remember(session) { session.devices() }
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var inspecting by remember { mutableStateOf<String?>(null) }
    var confirmingOthers by remember { mutableStateOf(false) }
    // Страница сервера закрылась — правда уже другая: перечитать.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(store, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { store.load() }
    }
    LaunchedEffect(state.pageToOpen) {
        val page = state.pageToOpen ?: return@LaunchedEffect
        store.pageOpened()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, page.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        state.current?.let { current ->
            SectionTitle(R.string.devices_this_device)
            Group(
                { DeviceRow(current, ending = false) { inspecting = current.id } },
                *if (state.others.isEmpty()) emptyArray() else arrayOf<@Composable () -> Unit>({
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.devices_end_others)) },
                        leadingContent = { Icon(Icons.Outlined.PanTool, contentDescription = null) },
                        modifier = Modifier.clickable { confirmingOthers = true },
                        colors = ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                            headlineColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }),
            )
            if (state.others.isNotEmpty()) Hint(R.string.devices_end_others_footer)
        }
        when {
            state.hasLoaded -> {
                SectionTitle(R.string.devices_others)
                if (state.others.isEmpty()) {
                    Hint(R.string.devices_no_others)
                } else {
                    Group(*state.others.map { device -> @Composable { DeviceRow(device, device.id in state.ending) { inspecting = device.id } } }.toTypedArray())
                }
            }
            state.isLoading -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalAccent.current.control)
            }
            else -> Group({
                ListItem(
                    headlineContent = { Text(stringResource(R.string.devices_retry)) },
                    modifier = Modifier.clickable { scope.launch { store.load() } },
                    colors = rowColors(),
                )
            })
        }
    }

    inspecting?.let { id ->
        val device = state.devices.firstOrNull { it.id == id }
        // Сессия пропала из списка после завершения отсюда — сервер согласился: карточка закрывается сама.
        if (device == null) {
            if (state.hasLoaded) inspecting = null
        } else {
            DeviceSheet(device, store, ending = id in state.ending, onDismiss = { inspecting = null })
        }
    }
    if (confirmingOthers) {
        AlertDialog(
            onDismissRequest = { confirmingOthers = false },
            title = { Text(stringResource(R.string.devices_end_others_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingOthers = false
                    scope.launch { store.endOthers() }
                }) { Text(stringResource(R.string.devices_end_others), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingOthers = false }) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
    EndingDialogs(store, state)
}

/** Пароль, которого просит сервер, и слово о том, что не вышло. */
@Composable
private fun EndingDialogs(store: DevicesStore, state: DevicesStore.State) {
    val scope = rememberCoroutineScope()
    state.awaitingPassword?.let { ids ->
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = store::passwordDismissed,
            title = { Text(stringResource(R.string.devices_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
                    Text(stringResource(R.string.devices_password_message))
                    EntryField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = stringResource(R.string.devices_password_field),
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = {
                        val typed = password
                        store.passwordDismissed()
                        scope.launch { store.end(ids, typed) }
                    },
                ) { Text(stringResource(R.string.devices_end)) }
            },
            dismissButton = { TextButton(onClick = store::passwordDismissed) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = store::failureShown,
            text = { Text(stringResource(failure.message)) },
            confirmButton = { TextButton(onClick = store::failureShown) { Text(stringResource(R.string.devices_done)) } },
        )
    }
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close),
    )
}

/** Одна сессия: что это, на чём работает, где и когда видели — три строки рядом с цветной плиткой, как у Telegram. */
@Composable
private fun DeviceRow(device: AccountDevice, ending: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.title, maxLines = 1) },
        supportingContent = {
            Column {
                device.detail?.let { Text(it, maxLines = 1) }
                Seen(device)
            }
        },
        leadingContent = { PlatformTile(device.platform, Theme.Size.avatarToolbar) },
        trailingContent = if (ending) {
            { CircularProgressIndicator(Modifier.size(Theme.Size.uploadRing), strokeWidth = Theme.Size.waveformBar) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
        colors = rowColors(),
    )
}

/** «в сети» у этого устройства; у остальных — где последний раз и как давно. */
@Composable
private fun Seen(device: AccountDevice) {
    if (device.isCurrent) {
        Text(stringResource(R.string.devices_online), color = LocalAccent.current.color, maxLines = 1)
        return
    }
    val ago = device.lastSeenAt?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
    val text = listOfNotNull(device.lastSeenIp, ago).joinToString(" · ").ifEmpty { device.id }
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
}

/**
 * Плитка вида устройства. У Telegram каждый вид своего цвета, чтобы список читался глазами раньше слов: здесь роли схемы,
 * чтобы плитки сидели правильно в обеих темах.
 */
@Composable
internal fun PlatformTile(platform: DevicePlatform, side: androidx.compose.ui.unit.Dp) {
    val scheme = MaterialTheme.colorScheme
    val (icon, container, content) = when (platform) {
        DevicePlatform.IPHONE -> Triple(Icons.Outlined.PhoneIphone, scheme.primaryContainer, scheme.onPrimaryContainer)
        DevicePlatform.IPAD -> Triple(Icons.Outlined.TabletMac, scheme.primaryContainer, scheme.onPrimaryContainer)
        DevicePlatform.MAC -> Triple(Icons.Outlined.Laptop, scheme.secondaryContainer, scheme.onSecondaryContainer)
        DevicePlatform.ANDROID -> Triple(Icons.Outlined.PhoneAndroid, scheme.tertiaryContainer, scheme.onTertiaryContainer)
        DevicePlatform.WEB -> Triple(Icons.Outlined.Language, scheme.errorContainer, scheme.onErrorContainer)
        DevicePlatform.DESKTOP -> Triple(Icons.Outlined.DesktopWindows, scheme.secondaryContainer, scheme.onSecondaryContainer)
        DevicePlatform.UNKNOWN -> Triple(Icons.Outlined.QuestionMark, scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
    Box(
        Modifier.size(side).background(container, RoundedCornerShape(Theme.Spacing.close)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon as ImageVector, contentDescription = null, tint = content) }
}

/** Сессия, раскрытая из списка: всё, что о ней знает сервер, и красная кнопка, что её завершает. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(device: AccountDevice, store: DevicesStore, ending: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            PlatformTile(device.platform, Theme.Size.avatarLarge)
            Text(device.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Theme.Spacing.roomy))
            val rows = buildList<Pair<Int, @Composable () -> Unit>> {
                device.detail?.let { add(R.string.devices_application to { Text(it) }) }
                add(
                    R.string.devices_last_seen to {
                        when {
                            device.isCurrent -> Text(stringResource(R.string.devices_online), color = LocalAccent.current.color)
                            device.lastSeenAt != null -> Text(
                                DateUtils.formatDateTime(
                                    LocalContext.current,
                                    device.lastSeenAt!!,
                                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
                                ),
                            )
                            else -> Text(stringResource(R.string.devices_unknown))
                        }
                    },
                )
                device.lastSeenIp?.let { add(R.string.devices_ip_address to { Text(it) }) }
                add(R.string.devices_device_id to { Text(device.id, fontFamily = FontFamily.Monospace) })
            }
            Group(
                *rows.map { (label, value) ->
                    @Composable {
                        ListItem(
                            headlineContent = { Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = value,
                            colors = rowColors(),
                        )
                    }
                }.toTypedArray(),
            )
            if (!device.isCurrent) {
                Group({
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.devices_end_session), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                        trailingContent = if (ending) {
                            { CircularProgressIndicator(Modifier.size(Theme.Size.uploadRing), strokeWidth = Theme.Size.waveformBar) }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable(enabled = !ending) { confirming = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = MaterialTheme.colorScheme.error),
                    )
                })
                if (store.isManagedByServer) Hint(R.string.devices_managed_footer)
            }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.devices_end_confirm)) },
            text = { Text(device.title) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch { store.end(listOf(device.id)) }
                }) { Text(stringResource(R.string.devices_end), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.devices_cancel)) } },
        )
    }
}

/**
 * Карточка одного сеанса поверх чего угодно — баннера «новый вход» ([wtf.iskra.features.rooms.NewSignInBanner]): сама читает список, показывает сеанс
 * и закрывается, когда он пропал из списка после завершения, — это и значит «завершён»: сервер согласился, а не кнопка нажата. Порт `DeviceDetailSheet`
 * в роли, где её зовут не с экрана «Устройства».
 */
@Composable
internal fun DeviceCard(device: AccountDevice, store: DevicesStore, onEnded: () -> Unit, onDismiss: () -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(store) { store.load() }
    val current = state.devices.firstOrNull { it.id == device.id }
    // Пока список не пришёл, показывается то, что баннер уже знает; пропал после загрузки — завершён.
    LaunchedEffect(current, state.hasLoaded) {
        if (current == null && state.hasLoaded) {
            onEnded()
            onDismiss()
        }
    }
    DeviceSheet(current ?: device, store, ending = device.id in state.ending, onDismiss = onDismiss)
    EndingDialogs(store, state)
}
