package wtf.iskra.features.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppSettings
import wtf.iskra.design.AccentTheme
import wtf.iskra.design.AppearanceMode
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.design.resolveHere
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar

/** Куда ведут настройки. Навигации-фреймворка нет: экран — состояние, «назад» — шаг по стеку. */
enum class SettingsPage { MAIN, SECURITY, APPEARANCE, BETA, ABOUT }

/**
 * Настройки (`SettingsView` на iOS, docs/PROMPT.md, 12.8). Пока — то, что можно сделать без
 * остальных этапов: профиль, оформление, бета, о приложении, выход. Безопасность, устройства,
 * уведомления и прочее — с их этапами.
 */
@Composable
fun SettingsScreen(
    session: UserSession,
    settings: AppSettings,
    page: SettingsPage,
    onNavigate: (SettingsPage?) -> Unit,
    onSignOut: () -> Unit,
    onDemo: (Boolean) -> Unit,
    appVersion: String,
) {
    BackHandler { onNavigate(if (page == SettingsPage.MAIN) null else SettingsPage.MAIN) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopBar(
            title = when (page) {
                SettingsPage.MAIN -> R.string.settings_title
                SettingsPage.APPEARANCE -> R.string.settings_appearance
                SettingsPage.SECURITY -> R.string.settings_security
                SettingsPage.BETA -> R.string.beta_title
                SettingsPage.ABOUT -> R.string.about_title
            },
            onBack = { onNavigate(if (page == SettingsPage.MAIN) null else SettingsPage.MAIN) },
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.MAIN -> Main(session, onNavigate, onSignOut)
                SettingsPage.APPEARANCE -> Appearance(settings)
                SettingsPage.SECURITY -> Security(session)
                SettingsPage.BETA -> Beta(settings, session.isDemo, onDemo)
                SettingsPage.ABOUT -> About(appVersion)
            }
        }
    }
}

@Composable
private fun TopBar(title: Int, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.tight, vertical = Theme.Spacing.close),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Main(session: UserSession, onNavigate: (SettingsPage?) -> Unit, onSignOut: () -> Unit) {
    val profile by session.profile.collectAsStateWithLifecycle()
    LaunchedEffect(session) { session.loadProfile() }
    var confirming by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(Theme.Spacing.generous),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        Avatar(profile.displayName ?: session.userId, profile.avatarUrl, Theme.Size.avatarProfile, seed = session.userId)
        Text(profile.displayName ?: session.userId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(session.userId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    Link(Icons.Outlined.Shield, R.string.settings_security) { onNavigate(SettingsPage.SECURITY) }
    Link(Icons.Outlined.Palette, R.string.settings_appearance) { onNavigate(SettingsPage.APPEARANCE) }
    Link(Icons.Outlined.Science, R.string.beta_title) { onNavigate(SettingsPage.BETA) }
    Link(Icons.Outlined.Info, R.string.settings_about) { onNavigate(SettingsPage.ABOUT) }
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { confirming = true }.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.roomy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
    ) {
        Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.account_sign_out), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.account_sign_out)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSignOut()
                }) { Text(stringResource(R.string.account_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.room_list_swipe_cancel)) } },
        )
    }
}

@Composable
private fun Link(icon: ImageVector, title: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.roomy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
    ) {
        Icon(icon, contentDescription = null, tint = LocalAccent.current.control)
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Theme.Spacing.roomy, end = Theme.Spacing.roomy, top = Theme.Spacing.generous, bottom = Theme.Spacing.close),
    )
}

/** Тема и цвет приложения (`AppearanceView`). Обои, режим лиц и иконка — следующими пунктами. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Appearance(settings: AppSettings) {
    val accentName by settings.accent.collectAsStateWithLifecycle()
    val modeName by settings.appearance.collectAsStateWithLifecycle()
    val accent = AccentTheme.fromStorage(accentName)
    val mode = AppearanceMode.fromStorage(modeName)

    SectionTitle(R.string.appearance_theme)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy)) {
        AppearanceMode.entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == mode,
                onClick = { settings.setAppearance(option.storageName) },
                shape = SegmentedButtonDefaults.itemShape(index, AppearanceMode.entries.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = LocalAccent.current.control.copy(alpha = Theme.Opacity.SELECTED_TAB)),
            ) {
                Text(
                    stringResource(
                        when (option) {
                            AppearanceMode.SYSTEM -> R.string.appearance_mode_system
                            AppearanceMode.LIGHT -> R.string.appearance_mode_light
                            AppearanceMode.DARK -> R.string.appearance_mode_dark
                        },
                    ),
                )
            }
        }
    }

    SectionTitle(R.string.appearance_accent)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        val dark = LocalAccent.current.isDark
        AccentTheme.choices().forEach { option ->
            val colors = option.resolveHere(dark)
            val label = stringResource(accentName(option))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = { settings.setAccent(option.storageName) },
                    modifier = Modifier
                        .size(Theme.Size.swatch)
                        .clip(CircleShape)
                        .background(colors.color)
                        .border(Theme.Border.hairline, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                ) {
                    if (option == accent) Icon(Icons.Filled.Check, contentDescription = label, tint = colors.foreground)
                }
            }
        }
    }
    Text(
        stringResource(accentName(accent)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Theme.Spacing.roomy),
    )
}

private fun accentName(theme: AccentTheme): Int = when (theme) {
    AccentTheme.SYSTEM -> R.string.appearance_mode_system
    AccentTheme.RED -> R.string.appearance_color_red
    AccentTheme.ORANGE -> R.string.appearance_color_orange
    AccentTheme.YELLOW -> R.string.appearance_color_yellow
    AccentTheme.GREEN -> R.string.appearance_color_green
    AccentTheme.CYAN -> R.string.appearance_color_cyan
    AccentTheme.BLUE -> R.string.appearance_color_blue
    AccentTheme.VIOLET -> R.string.appearance_color_violet
    AccentTheme.MONO -> R.string.appearance_color_mono
}

/** Бета-функции (`BetaView`): существа вместо инициалов и демо-сервер. */
@Composable
private fun Beta(settings: AppSettings, isDemo: Boolean, onDemo: (Boolean) -> Unit) {
    val creatures by settings.creatures.collectAsStateWithLifecycle()
    val forced by settings.forceCreatures.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.beta_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Theme.Spacing.roomy),
    )
    Toggle(R.string.beta_blobatar, R.string.beta_blobatar_help, creatures, settings::setCreatures)
    Toggle(R.string.beta_blobatar_force, R.string.beta_blobatar_force_help, forced, enabled = creatures, onChange = settings::setForceCreatures)
    SectionTitle(R.string.beta_developers)
    Toggle(R.string.beta_mock_backend, R.string.beta_mock_backend_help, isDemo, onDemo)
}

@Composable
private fun Toggle(title: Int, help: Int, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(Theme.Spacing.roomy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Theme.Spacing.roomy))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current.control, checkedThumbColor = LocalAccent.current.foreground),
        )
    }
}

/**
 * О приложении (`AboutView`): версия и лицензии того, что внутри. Нотис blobatar обязателен (MIT), и
 * Inter (SIL OFL) — с ним. Проверка обновлений — с первым релизом.
 */
@Composable
private fun About(appVersion: String) {
    Column(Modifier.padding(Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        Text(stringResource(R.string.settings_app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
            Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyMedium)
            Text(appVersion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(stringResource(R.string.about_free_software), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionTitle(R.string.about_licences)
    Licence(R.raw.licence_blobatar)
    Licence(R.raw.licence_inter)
}

@Composable
private fun Licence(resource: Int) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = remember(resource) { context.resources.openRawResource(resource).bufferedReader().use { it.readText() } }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
    )
    HorizontalDivider(Modifier.padding(horizontal = Theme.Spacing.roomy))
}

/**
 * Безопасность (`SecurityView`) отвечает на один вопрос — «прочитаю ли я всё это на новом телефоне», —
 * и словами человека: «резервная копия», «код восстановления». Код показывается один раз, на экране,
 * который закрывается единственной кнопкой «Я сохранил код».
 */
@Composable
private fun Security(session: UserSession) {
    val recovery = session.recovery
    val protection by recovery.protection.collectAsStateWithLifecycle()
    val verified by recovery.isDeviceVerified.collectAsStateWithLifecycle()
    val working by recovery.isWorking.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var code by remember { mutableStateOf<wtf.iskra.core.session.RecoveryKey?>(null) }
    var typed by remember { mutableStateOf("") }
    var confirmingNew by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<wtf.iskra.core.session.AccountActionFailure?>(null) }
    LaunchedEffect(recovery) { recovery.load() }

    val shown = code
    if (shown != null) {
        Column(Modifier.padding(Theme.Spacing.generous), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy), horizontalAlignment = Alignment.CenterHorizontally) {
            wtf.iskra.features.recovery.RecoveryCode(shown, onSaved = { code = null })
        }
        return
    }

    val (title, help) = when (protection) {
        wtf.iskra.core.session.HistoryProtection.ON -> R.string.encryption_on to R.string.encryption_on_help
        wtf.iskra.core.session.HistoryProtection.KEY_NEEDED -> R.string.encryption_key_needed to R.string.encryption_key_needed_help
        else -> R.string.encryption_off to R.string.encryption_off_help
    }
    Column(Modifier.padding(Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        when (protection) {
            wtf.iskra.core.session.HistoryProtection.OFF, wtf.iskra.core.session.HistoryProtection.UNKNOWN ->
                wtf.iskra.design.PrimaryActionButton(
                    onClick = { scope.launch { code = recovery.protectHistory() ?: run { failure = recovery.failure; null } } },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.encryption_protect)) }
            wtf.iskra.core.session.HistoryProtection.KEY_NEEDED -> {
                wtf.iskra.design.EntryField(value = typed, onValueChange = { typed = it }, placeholder = stringResource(R.string.encryption_key_placeholder))
                wtf.iskra.design.PrimaryActionButton(
                    onClick = { scope.launch { if (recovery.restore(typed)) typed = "" else failure = recovery.failure } },
                    enabled = typed.isNotBlank() && !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.encryption_restore)) }
            }
            wtf.iskra.core.session.HistoryProtection.ON -> {
                wtf.iskra.design.SecondaryActionButton(onClick = { confirmingNew = true }, enabled = !working, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.encryption_new_key))
                }
                Text(stringResource(R.string.encryption_new_key_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!verified) {
        SectionTitle(R.string.encryption_device_unverified)
        Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            Text(stringResource(R.string.verification_why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            wtf.iskra.design.SecondaryActionButton(onClick = { scope.launch { session.verification.start() } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.verification_verify))
            }
        }
    }

    if (confirmingNew) {
        AlertDialog(
            onDismissRequest = { confirmingNew = false },
            title = { Text(stringResource(R.string.recovery_new_title)) },
            text = { Text(stringResource(R.string.recovery_new_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingNew = false
                    scope.launch { code = recovery.makeNewKey() ?: run { failure = recovery.failure; null } }
                }) { Text(stringResource(R.string.recovery_new_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingNew = false }) { Text(stringResource(R.string.room_list_swipe_cancel)) } },
        )
    }
    failure?.let {
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(it.explanation)) },
        )
    }
}
