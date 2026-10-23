package wtf.iskra.features.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.notifications.RoomAlerts
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppSettings
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.push.PushTransport
import wtf.iskra.features.push.RootPushes

/**
 * «Уведомления» (`NotificationsView` на iOS, docs/PROMPT.md, 10.6). Сверху — то, что стоит выше любых
 * правил: пропускает ли система и доезжают ли пуши до телефона. Ниже — правила аккаунта на сервере:
 * они действуют на всех устройствах, а настройка отдельного чата всегда главнее. Без сети правила не
 * трогаются: переключатель, который не может записаться, — обещание, которого нет.
 */
@Composable
internal fun Notifications(session: UserSession, settings: AppSettings, pushes: RootPushes?) {
    val context = LocalContext.current
    // Перечитывается при каждом возвращении: разрешение и дистрибьютор меняют в других приложениях.
    var visits by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        visits += 1
        onPauseOrDispose { }
    }

    SystemPermission(context, settings, visits)
    if (pushes != null) Delivery(context, pushes, visits)
    Rules(session)
}

@Composable
private fun SystemPermission(context: Context, settings: AppSettings, visits: Int) {
    val status = remember(visits) { permission(context, settings) }
    SectionTitle(R.string.notifications_system)
    Group(
        *buildList<@Composable () -> Unit> {
            add {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(
                                when (status) {
                                    Permission.ON -> R.string.notifications_system_on
                                    Permission.OFF -> R.string.notifications_system_off
                                    Permission.UNKNOWN -> R.string.notifications_system_unknown
                                },
                            ),
                        )
                    },
                    colors = rowColors(),
                )
            }
            if (status != Permission.ON) {
                add {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.notifications_open_system)) },
                        modifier = Modifier.clickable { context.startActivity(systemSettings(context)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = LocalAccent.current.control),
                    )
                }
            }
        }.toTypedArray(),
    )
    if (status != Permission.ON) Footer(R.string.notifications_system_footer)
}

/** Чем пуши доезжают: у `foss` — какой дистрибьютор UnifiedPush; у `gms` — есть ли FCM в сборке. */
@Composable
private fun Delivery(context: Context, pushes: RootPushes, visits: Int) {
    val transport = pushes.push.transport
    val target by pushes.target.collectAsStateWithLifecycle()
    val state = remember(visits, target) { transport.state(context) }
    val distributors = remember(visits) { transport.distributors(context) }
    var chosen by remember(visits) { mutableIntStateOf(0) }
    val selected = remember(visits, chosen, target) { transport.distributor(context) }

    SectionTitle(R.string.push_transport)
    Group({
        ListItem(
            headlineContent = {
                Text(
                    stringResource(
                        when (state) {
                            PushTransport.State.READY -> R.string.push_transport_ready
                            PushTransport.State.WAITING -> R.string.push_transport_waiting
                            PushTransport.State.NO_DISTRIBUTOR -> R.string.push_transport_no_distributor
                            PushTransport.State.UNAVAILABLE -> R.string.push_transport_unavailable
                        },
                    ),
                )
            },
            colors = rowColors(),
        )
    })
    if (distributors.isNotEmpty()) {
        SectionTitle(R.string.push_transport_distributor)
        Group(
            *distributors.map { distributor ->
                @Composable {
                    ChoiceText(distributor.label, selected = distributor.packageName == selected) {
                        transport.choose(context, distributor.packageName)
                        chosen += 1
                    }
                }
            }.toTypedArray(),
        )
    }
    if (state == PushTransport.State.NO_DISTRIBUTOR) {
        Group({
            ListItem(
                headlineContent = { Text(stringResource(R.string.push_transport_learn_more)) },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UNIFIED_PUSH_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = LocalAccent.current.control),
            )
        })
    }
}

/** Правила аккаунта на сервере (`NotificationRulesStore`). */
@Composable
private fun Rules(session: UserSession) {
    val store = session.notificationRules
    val rules by store.state.collectAsStateWithLifecycle()
    val rooms by session.rooms.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { store.load() }
    val enabled = !rooms.isOffline

    when {
        rules.didFailToLoad -> Footer(R.string.notifications_failed)
        !rules.isLoaded -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        else -> {
            SectionTitle(R.string.notifications_direct)
            Alerts(rules.directChats, enabled) { scope.launch { store.setDefault(it, isOneToOne = true) } }
            SectionTitle(R.string.notifications_groups)
            Alerts(rules.groupChats, enabled) { scope.launch { store.setDefault(it, isOneToOne = false) } }
            Footer(R.string.notifications_defaults_footer)

            SectionTitle(R.string.notifications_mentions)
            Group(
                { Toggle(R.string.notifications_mentions_me, null, rules.mentionsMe, { scope.launch { store.setMentionsMe(it) } }, enabled) },
                { Toggle(R.string.notifications_room_mentions, null, rules.roomMentions, { scope.launch { store.setRoomMentions(it) } }, enabled) },
            )

            SectionTitle(R.string.notifications_other)
            Group(
                { Toggle(R.string.notifications_invitations, null, rules.invitations, { scope.launch { store.setInvitations(it) } }, enabled) },
                { Toggle(R.string.notifications_calls, null, rules.calls, { scope.launch { store.setCalls(it) } }, enabled) },
            )
            Footer(R.string.notifications_other_footer)
        }
    }

    rules.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = store::dismissFailure,
            confirmButton = { TextButton(onClick = store::dismissFailure) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(failure.explanation)) },
        )
    }
}

/** Все сообщения / только упоминания / выключены — те же слова, что у чата (`room_manage_alerts_*`). */
@Composable
private fun Alerts(current: RoomAlerts, enabled: Boolean, onSelect: (RoomAlerts) -> Unit) {
    Group(
        *RoomAlerts.entries.map { option ->
            @Composable {
                Choice(
                    title = when (option) {
                        RoomAlerts.ALL -> R.string.room_manage_alerts_all
                        RoomAlerts.MENTIONS -> R.string.room_manage_alerts_mentions
                        RoomAlerts.MUTED -> R.string.room_manage_alerts_muted
                    },
                    selected = option == current,
                ) { if (enabled) onSelect(option) }
            }
        }.toTypedArray(),
    )
}

@Composable
internal fun Footer(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close),
    )
}

private enum class Permission { ON, OFF, UNKNOWN }

/** Пропускает ли система. «Не спрашивали» — отдельно: это не отказ, и страшным словом его не назвать. */
private fun permission(context: Context, settings: AppSettings): Permission {
    if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return Permission.ON
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return Permission.OFF
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return if (granted || settings.askedForNotifications) Permission.OFF else Permission.UNKNOWN
}

/** Системные настройки уведомлений этого приложения — туда, где их меняют. */
private fun systemSettings(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private const val UNIFIED_PUSH_PAGE = "https://unifiedpush.org/users/distributors/"
