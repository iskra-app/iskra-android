package wtf.iskra.features.settings

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import wtf.iskra.core.media.MediaCompression
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppSettings
import wtf.iskra.core.timeline.ThreadSettings
import wtf.iskra.features.RootModel
import wtf.iskra.features.push.RootPushes
import wtf.iskra.features.update.UpdateRow
import wtf.iskra.features.update.UpdateSheet
import wtf.iskra.features.update.Updates
import wtf.iskra.core.update.UpdateOffer
import wtf.iskra.features.signin.WebAuthentication
import wtf.iskra.features.signin.SignInScreen
import wtf.iskra.features.signin.SignInModel
import wtf.iskra.features.signin.MockSignIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import wtf.iskra.design.AccentTheme
import wtf.iskra.design.AppearanceMode
import wtf.iskra.design.iskraTypography
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.design.resolveHere
import wtf.iskra.features.R
import wtf.iskra.features.support.Avatar

/** Куда ведут настройки. Навигации-фреймворка нет: экран — состояние, «назад» — шаг по стеку. */
enum class SettingsPage { MAIN, PROFILE, SECURITY, DEVICES, PRIVACY, BLOCKED, STORAGE, NOTIFICATIONS, APPEARANCE, CHATS, FOLDERS, MEDIA, BETA, ABOUT, LIBRARIES, REACTIONS }

/**
 * Настройки (`SettingsView` на iOS, docs/PROMPT.md, 12.8). Пока — то, что можно сделать без
 * остальных этапов: профиль, оформление, бета, о приложении, выход. Безопасность, устройства,
 * уведомления и прочее — с их этапами.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    session: UserSession,
    settings: AppSettings,
    page: SettingsPage,
    onNavigate: (SettingsPage?) -> Unit,
    onSignOut: () -> Unit,
    onDemo: (Boolean) -> Unit,
    appVersion: String,
    /** Аккаунты устройства и что с ними можно сделать — всё делает корень (`RootModel`). */
    accounts: RootModel.Accounts = RootModel.Accounts(),
    onSwitchAccount: (String) -> Unit = {},
    /** Кэш очищен: клиент этого сеанса кончен, и приложение открывает сеанс заново (`RootModel.reopenAfterClearing`). */
    onStorageCleared: () -> Unit = {},
    onAddAccount: () -> Unit = {},
    onSignOutOther: (String) -> Unit = {},
    /** Браузер для входа — «Бета → Демо-вход» проходит тот же вход, что настоящий. */
    browser: WebAuthentication? = null,
    /** Пуши: чем доезжают до телефона. Null — без пушей (демо-вход). */
    pushes: RootPushes? = null,
    /** Обновления изнутри приложения. Null — без них (демо-вход). */
    updates: Updates? = null,
    /** Перед установкой обновления сеанс закрывается, после неудачной — открывается снова (`RootModel`). */
    beforeUpdate: suspend () -> Unit = {},
    afterFailedUpdate: suspend () -> Unit = {},
    /** Список библиотек (`R.raw.aboutlibraries` из `:app`); 0 — его нет. */
    librariesResource: Int = 0,
) {
    val offer by (updates?.checker?.offer ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsStateWithLifecycle()
    var showingUpdate by remember { mutableStateOf(false) }
    val available = offer as? UpdateOffer.Available
    if (showingUpdate && updates != null && available != null) {
        UpdateSheet(updates, available, onDismiss = { showingUpdate = false }, beforeInstall = beforeUpdate, afterFailure = afterFailedUpdate)
    }
    // Назад — на страницу выше: библиотеки живут внутри «О приложении».
    val up = when (page) {
        SettingsPage.MAIN -> null
        SettingsPage.LIBRARIES -> SettingsPage.ABOUT
        SettingsPage.BLOCKED -> SettingsPage.PRIVACY
        // Устройства и приватность — внутри «Безопасности», как на iOS.
        SettingsPage.DEVICES, SettingsPage.PRIVACY -> SettingsPage.SECURITY
        SettingsPage.BETA -> SettingsPage.ABOUT
        else -> SettingsPage.MAIN
    }
    BackHandler { onNavigate(up) }
    // Большой заголовок, который сворачивается в панель, когда страницу листают, — как в настройках Android.
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).nestedScroll(scroll.nestedScrollConnection)) {
        TopBar(
            scroll = scroll,
            title = when (page) {
                SettingsPage.MAIN -> R.string.settings_title
                SettingsPage.PROFILE -> R.string.account_edit_title
                SettingsPage.APPEARANCE -> R.string.settings_appearance
                SettingsPage.MEDIA -> R.string.media_title
                SettingsPage.CHATS -> R.string.settings_chats
                SettingsPage.REACTIONS -> R.string.settings_reactions
                SettingsPage.FOLDERS -> R.string.folders_title
                SettingsPage.SECURITY -> R.string.settings_security
                SettingsPage.DEVICES -> R.string.devices_title
                SettingsPage.PRIVACY -> R.string.privacy_title
                SettingsPage.BLOCKED -> R.string.settings_blocked
                SettingsPage.STORAGE -> R.string.storage_title
                SettingsPage.NOTIFICATIONS -> R.string.notifications_title
                SettingsPage.BETA -> R.string.beta_title
                SettingsPage.ABOUT -> R.string.about_title
                SettingsPage.LIBRARIES -> R.string.about_libraries
            },
            onBack = { onNavigate(up) },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            when (page) {
                SettingsPage.MAIN -> {
                    if (available != null) Group({ UpdateRow(available) { showingUpdate = true } })
                    Main(session, onNavigate, onSignOut, hasSeveralAccounts = accounts.hasSeveral) {
                        if (accounts.isAvailable) AccountsSection(accounts, onSwitchAccount, onAddAccount, onSignOutOther)
                    }
                }
                SettingsPage.PROFILE -> ProfileEdit(session)
                SettingsPage.APPEARANCE -> Appearance(settings)
                SettingsPage.MEDIA -> Media(settings)
                SettingsPage.CHATS -> Chats(settings)
                SettingsPage.REACTIONS -> ReactionsPage(settings)
                SettingsPage.FOLDERS -> FoldersPage(session)
                SettingsPage.SECURITY -> Security(session, onNavigate)
                SettingsPage.DEVICES -> DevicesPage(session)
                SettingsPage.PRIVACY -> PrivacyPage(settings) { onNavigate(SettingsPage.BLOCKED) }
                SettingsPage.BLOCKED -> BlockedPage(session)
                SettingsPage.STORAGE -> StoragePage(session, onStorageCleared)
                SettingsPage.NOTIFICATIONS -> Notifications(session, settings, pushes)
                SettingsPage.BETA -> Beta(settings, session.isDemo, onDemo, browser, appVersion)
                SettingsPage.LIBRARIES -> Libraries(librariesResource)
                SettingsPage.ABOUT -> About(appVersion, onBeta = { onNavigate(SettingsPage.BETA) }) {
                    // Выше длинных текстов лицензий: под ними их никто не нашёл бы.
                    if (updates != null && updates.checker.isInBuild) UpdateChecks(settings, updates) { showingUpdate = true }
                    if (librariesResource != 0) {
                        Group({ Link(Icons.Outlined.Info, R.string.about_libraries) { onNavigate(SettingsPage.LIBRARIES) } })
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(scroll: TopAppBarScrollBehavior, title: Int, onBack: () -> Unit) {
    LargeTopAppBar(
        title = { Text(stringResource(title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
            }
        },
        windowInsets = WindowInsets(0),
        scrollBehavior = scroll,
    )
}

/**
 * Группа строк, как в настройках Android: плитки на поверхности на тон выше страницы, крупные углы
 * у группы и маленькие между её строками. Строки внутри — [ListItem] без своей заливки.
 */
@Composable
internal fun Group(vararg rows: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.groupGap),
    ) {
        rows.forEachIndexed { index, row ->
            val top = if (index == 0) Theme.Radius.group else Theme.Radius.groupInner
            val bottom = if (index == rows.lastIndex) Theme.Radius.group else Theme.Radius.groupInner
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) { row() }
        }
    }
}

/** Строка без своей заливки: её даёт [Group]. */
@Composable
internal fun rowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun Main(
    session: UserSession,
    onNavigate: (SettingsPage?) -> Unit,
    onSignOut: () -> Unit,
    hasSeveralAccounts: Boolean = false,
    accountsSection: @Composable () -> Unit = {},
) {
    val profile by session.profile.collectAsStateWithLifecycle()
    LaunchedEffect(session) { session.loadProfile() }
    var confirming by remember { mutableStateOf(false) }
    var showingCode by remember { mutableStateOf(false) }
    if (showingCode) {
        wtf.iskra.features.support.QrCardSheet(
            name = profile.displayName ?: session.userId,
            subtitle = session.userId,
            link = wtf.iskra.core.rooms.MatrixLink.User(session.userId),
            onDismiss = { showingCode = false },
        )
    }

    Column(
        Modifier.fillMaxWidth().padding(Theme.Spacing.generous),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        Avatar(profile.displayName ?: session.userId, profile.avatarUrl, Theme.Size.avatarProfile, seed = session.userId)
        Text(profile.displayName ?: session.userId, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(session.userId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Имя и картинка меняются на своём экране, а не здесь: экран, который читают, не должен
        // править себя от случайного касания (`ProfileEditView` на iOS).
        TextButton(onClick = { onNavigate(SettingsPage.PROFILE) }) { Text(stringResource(R.string.account_edit)) }
        TextButton(onClick = { showingCode = true }) {
            Icon(Icons.Outlined.QrCode2, contentDescription = null)
            Text(stringResource(R.string.qr_title), modifier = Modifier.padding(start = Theme.Spacing.close))
        }
    }
    accountsSection()
    // «Выйти» — или, когда аккаунтов несколько и неясно, из какого, — «Выйти из Саши».
    val signOutTitle = if (hasSeveralAccounts) {
        stringResource(R.string.accounts_sign_out_of, profile.displayName ?: session.userId)
    } else {
        stringResource(R.string.account_sign_out)
    }
    // Порядок — как в `SettingsView` на iOS. «Бета» здесь строкой нет: туда ведёт тройное нажатие на значок в «О приложении».
    Group(
        { Link(Icons.Outlined.Palette, R.string.settings_appearance) { onNavigate(SettingsPage.APPEARANCE) } },
        *languageRow(),
        { Link(Icons.Outlined.EmojiEmotions, R.string.settings_reactions) { onNavigate(SettingsPage.REACTIONS) } },
        { Link(Icons.Outlined.Forum, R.string.settings_chats) { onNavigate(SettingsPage.CHATS) } },
        { Link(Icons.Outlined.Folder, R.string.folders_title) { onNavigate(SettingsPage.FOLDERS) } },
        { Link(Icons.Outlined.Notifications, R.string.settings_notifications) { onNavigate(SettingsPage.NOTIFICATIONS) } },
        { Link(Icons.Outlined.Shield, R.string.settings_security) { onNavigate(SettingsPage.SECURITY) } },
        { Link(Icons.Outlined.PhotoLibrary, R.string.settings_media) { onNavigate(SettingsPage.MEDIA) } },
        { Link(Icons.Outlined.Storage, R.string.settings_storage) { onNavigate(SettingsPage.STORAGE) } },
        { Link(Icons.Outlined.Info, R.string.settings_about) { onNavigate(SettingsPage.ABOUT) } },
    )
    Group({
        ListItem(
            headlineContent = { Text(signOutTitle) },
            leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            modifier = Modifier.clickable { confirming = true },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = MaterialTheme.colorScheme.error,
                leadingIconColor = MaterialTheme.colorScheme.error,
            ),
        )
    })

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(signOutTitle) },
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
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        leadingContent = { Icon(icon, contentDescription = null, tint = LocalAccent.current.control) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
        colors = rowColors(),
    )
}

@Composable
internal fun SectionTitle(title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = LocalAccent.current.color,
        modifier = Modifier.padding(start = Theme.Spacing.generous + Theme.Spacing.close, end = Theme.Spacing.roomy, top = Theme.Spacing.roomy),
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

    WallpaperSection(settings)
    AppIconSection()
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

/**
 * Что делать с фото и видео по дороге наружу (`MediaSettingsView`): без метаданных и сжато — оба
 * включены по умолчанию. Читается в момент отправки (`TimelineStore.sendPhoto`).
 */
@Composable
private fun Media(settings: AppSettings) {
    val media by settings.media.collectAsStateWithLifecycle()
    // Первой: она про то, что *приходит*, и с ней приходят на этот экран при роуминге (`MediaSettingsView`).
    val autoDownload by settings.autoDownload.collectAsStateWithLifecycle()
    SectionTitle(R.string.media_auto_download)
    Group(
        *wtf.iskra.core.media.MediaAutoDownload.entries.map { option ->
            @Composable {
                Choice(
                    title = when (option) {
                        wtf.iskra.core.media.MediaAutoDownload.ALWAYS -> R.string.media_auto_download_always
                        wtf.iskra.core.media.MediaAutoDownload.WIFI -> R.string.media_auto_download_wifi
                        wtf.iskra.core.media.MediaAutoDownload.NEVER -> R.string.media_auto_download_never
                    },
                    selected = option == autoDownload,
                ) { settings.setAutoDownload(option) }
            }
        }.toTypedArray(),
    )
    // Одна подпись, та, что про выбранное: «Только Wi-Fi» не говорит, что показывают вместо картинки.
    Text(
        stringResource(
            when (autoDownload) {
                wtf.iskra.core.media.MediaAutoDownload.ALWAYS -> R.string.media_auto_download_always_help
                wtf.iskra.core.media.MediaAutoDownload.WIFI -> R.string.media_auto_download_wifi_help
                wtf.iskra.core.media.MediaAutoDownload.NEVER -> R.string.media_auto_download_never_help
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Theme.Spacing.roomy),
    )
    Group(
        { Toggle(R.string.media_strip_metadata, null, media.stripsMetadata, { settings.setMedia(media.copy(stripsMetadata = it)) }) },
        { Toggle(R.string.media_compress, null, media.compresses, { settings.setMedia(media.copy(compresses = it)) }) },
    )
    if (media.compresses) {
        SectionTitle(R.string.media_compression)
        // Списком, а не сегментами: «Средне (720p)» на немецком в треть ширины экрана не помещается.
        Group(
            *MediaCompression.entries.map { option ->
                @Composable {
                    Choice(
                        title = when (option) {
                            MediaCompression.STRONG -> R.string.media_compression_strong
                            MediaCompression.BALANCED -> R.string.media_compression_balanced
                            MediaCompression.LIGHT -> R.string.media_compression_light
                        },
                        selected = option == media.compression,
                    ) { settings.setMedia(media.copy(compression = option)) }
                }
            }.toTypedArray(),
        )
    }
}

/** «Чаты»: где треды и уходят ли ответы из ленты (`ThreadSettings`). Действует со следующего открытия чата. */
@Composable
private fun Chats(settings: AppSettings) {
    val threads by settings.threads.collectAsStateWithLifecycle()
    SectionTitle(R.string.chats_threads)
    Group(
        *ThreadSettings.Shown.entries.map { option ->
            @Composable {
                Choice(
                    title = when (option) {
                        ThreadSettings.Shown.ALWAYS -> R.string.chats_threads_always
                        ThreadSettings.Shown.IN_LARGE_ROOMS -> R.string.chats_threads_large_rooms
                        ThreadSettings.Shown.NEVER -> R.string.chats_threads_never
                    },
                    selected = option == threads.shown,
                ) { settings.setThreads(threads.copy(shown = option)) }
            }
        }.toTypedArray(),
    )
    Group({
        Toggle(
            R.string.chats_threads_hide_replies,
            R.string.chats_threads_help,
            threads.hidesReplies,
            { settings.setThreads(threads.copy(hidesReplies = it)) },
            enabled = threads.shown != ThreadSettings.Shown.NEVER,
        )
    })

    val service by settings.serviceEvents.collectAsStateWithLifecycle()
    SectionTitle(R.string.chats_service_section)
    Group(
        { Toggle(R.string.chats_hide_name_changes, null, service.hidesNameChanges, { settings.setServiceEvents(service.copy(hidesNameChanges = it)) }) },
        { Toggle(R.string.chats_hide_membership_changes, R.string.chats_service_help, service.hidesMembershipChanges, { settings.setServiceEvents(service.copy(hidesMembershipChanges = it)) }) },
    )
}

/** Бета-функции (`BetaView`): существа вместо инициалов и, в debug, демо-сервер. */
@Composable
private fun Beta(settings: AppSettings, isDemo: Boolean, onDemo: (Boolean) -> Unit, browser: WebAuthentication?, appVersion: String) {
    val creatures by settings.creatures.collectAsStateWithLifecycle()
    val forced by settings.forceCreatures.collectAsStateWithLifecycle()
    Text(
        stringResource(R.string.beta_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Theme.Spacing.roomy),
    )
    Group(
        { Toggle(R.string.beta_blobatar, R.string.beta_blobatar_help, creatures, settings::setCreatures) },
        { Toggle(R.string.beta_blobatar_force, R.string.beta_blobatar_force_help, forced, enabled = creatures, onChange = settings::setForceCreatures) },
    )
    // Для того, кто спрашивает «почему эта комната так себя ведёт»: JSON события в меню и все события в ленте (`BetaView`).
    val events by settings.serviceEvents.collectAsStateWithLifecycle()
    val source by settings.showsSource.collectAsStateWithLifecycle()
    Group(
        { Toggle(R.string.beta_shows_all_events, R.string.beta_shows_all_events_help, events.showsEverything, settings::setShowsAllEvents) },
        { Toggle(R.string.beta_shows_source, R.string.beta_shows_source_help, source, settings::setShowsSource) },
    )
    // Демо-сервер и демо-вход — только в debug (`DemoServers`), как `#if DEBUG` на iOS.
    if (!MockSignIn.isAvailable) return
    SectionTitle(R.string.beta_developers)
    if (browser != null) {
        Group(
            { Toggle(R.string.beta_mock_backend, R.string.beta_mock_backend_help, isDemo, onDemo) },
            { DemoSignIn(browser, appVersion) },
        )
    } else {
        Group({ Toggle(R.string.beta_mock_backend, R.string.beta_mock_backend_help, isDemo, onDemo) })
    }
}

/**
 * «Демо-вход»: настоящий экран входа, настоящий SDK, придуманный сервер (`MockSignIn`) — и сеанс,
 * который закрывается, едва открывшись: смысл в дороге внутрь, а не в том, что за ней. Аккаунт
 * устройства не трогается: у демо-входа свой стор во временном каталоге и секреты в памяти.
 */
@Composable
private fun DemoSignIn(browser: WebAuthentication, appVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf<String?>(null) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.beta_demo_sign_in)) },
        supportingContent = { Text(stringResource(R.string.beta_demo_sign_in_help)) },
        modifier = Modifier.clickable { scope.launch { address = withContext(Dispatchers.IO) { MockSignIn.start() } } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = LocalAccent.current.control),
    )
    address?.let { server ->
        val model = remember(server) {
            SignInModel(MockSignIn.authentication(context, appVersion), browser, scope, onSignedIn = { session ->
                signedIn = session.userId
                session.close()
                address = null
            }, address = server)
        }
        Dialog(
            onDismissRequest = { address = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    SignInScreen(model)
                    TextButton(
                        onClick = { address = null },
                        modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(Theme.Spacing.close),
                    ) { Text(stringResource(R.string.beta_close)) }
                }
            }
        }
    }
    signedIn?.let { who ->
        AlertDialog(
            onDismissRequest = { signedIn = null },
            confirmButton = { TextButton(onClick = { signedIn = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(R.string.beta_demo_signed_in, who)) },
        )
    }
}

@Composable
internal fun Toggle(title: Int, help: Int?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = help?.let { { Text(stringResource(it)) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        colors = rowColors(),
    )
}

/** Один из вариантов: строка с кружком справа, вся строка — нажатие. */
@Composable
internal fun Choice(title: Int, selected: Boolean, onSelect: () -> Unit) = ChoiceText(stringResource(title), selected, onSelect)

/** То же, со словами, которые не строка ресурса: имя приложения-дистрибьютора. */
@Composable
internal fun ChoiceText(title: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = rowColors(),
    )
}

/**
 * О приложении (`AboutView`): версия и лицензии того, что внутри. Нотис blobatar обязателен (MIT), и
 * Google Sans (SIL OFL) — с ним. Проверка обновлений — [UpdateChecks] под ним.
 */
@Composable
private fun About(appVersion: String, onBeta: () -> Unit, beforeLicences: @Composable () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val build = remember {
        runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toString() }.getOrDefault("")
    }
    val device = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"
    Column(
        Modifier.fillMaxWidth().padding(Theme.Spacing.roomy),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(AppIcon.current(context).preview),
            contentDescription = null,
            modifier = Modifier.size(Theme.Size.avatarProfile).tripleTap(onBeta).clip(androidx.compose.foundation.shape.RoundedCornerShape(Theme.Radius.appIcon(Theme.Size.avatarProfile.value).let { androidx.compose.ui.unit.Dp(it) })),
        )
        Text(stringResource(R.string.settings_app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.about_tagline_android),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    Group(
        { AboutRow(R.string.about_version, appVersion) },
        { if (build.isNotEmpty()) AboutRow(R.string.about_build, build) },
        { AboutRow(R.string.about_device, device) },
    )
    Group(
        { AboutLink(R.string.about_website, "iskra.wtf", "https://iskra.wtf") },
        { AboutLink(R.string.about_source_code, "github.com/iskra-app/iskra-android", "https://github.com/iskra-app/iskra-android") },
    )
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
        Text(stringResource(R.string.about_copyright), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.about_free_software), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    beforeLicences()
    SectionTitle(R.string.about_licences)
    Licence(R.raw.licence_blobatar)
    Licence(R.raw.licence_google_sans)
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
}

/**
 * Безопасность (`SecurityView`) отвечает на один вопрос — «прочитаю ли я всё это на новом телефоне», —
 * и словами человека: «резервная копия», «код восстановления». Код показывается один раз, на экране,
 * который закрывается единственной кнопкой «Я сохранил код».
 */
@Composable
private fun Security(session: UserSession, onNavigate: (SettingsPage?) -> Unit) {
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

    wtf.iskra.features.lock.LocalLock.current?.let { LockSection(it) }

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
    Group(
        { Link(Icons.Outlined.Devices, R.string.devices_title) { onNavigate(SettingsPage.DEVICES) } },
        { Link(Icons.Outlined.Lock, R.string.privacy_title) { onNavigate(SettingsPage.PRIVACY) } },
    )
}

/**
 * Замок приложения: включить и как скоро он закрывается после ухода. Не предлагает замок, которому устройство нечем открыть, — тот
 * запер бы человека снаружи собственных сообщений.
 */
@Composable
private fun LockSection(lock: wtf.iskra.features.lock.LockHooks) {
    val enabled by lock.appLock.isEnabled.collectAsStateWithLifecycle()
    val timeout by lock.appLock.timeout.collectAsStateWithLifecycle()
    val available = remember { lock.canAuthenticate() }
    SectionTitle(R.string.settings_lock)
    Group({ Toggle(R.string.settings_lock_enable, R.string.settings_lock_explanation, enabled, lock.appLock::setEnabled, enabled = available || enabled) })
    if (!available) {
        Text(
            stringResource(R.string.settings_lock_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Theme.Spacing.generous + Theme.Spacing.close),
        )
    }
    if (enabled) {
        SectionTitle(R.string.settings_lock_timeout)
        Group(
            *wtf.iskra.core.security.AppLockTimeout.entries.map { option ->
                @Composable {
                    Choice(
                        title = when (option) {
                            wtf.iskra.core.security.AppLockTimeout.IMMEDIATELY -> R.string.settings_lock_timeout_immediately
                            wtf.iskra.core.security.AppLockTimeout.AFTER_MINUTE -> R.string.settings_lock_timeout_after_minute
                            wtf.iskra.core.security.AppLockTimeout.AFTER_FIVE_MINUTES -> R.string.settings_lock_timeout_after_five_minutes
                        },
                        selected = option == timeout,
                    ) { lock.appLock.setTimeout(option) }
                }
            }.toTypedArray(),
        )
    }
}

/**
 * «Язык»: как на iPhone, выбор живёт в системных настройках приложения (Android 13+, `localeConfig` с теми же семью языками) —
 * строка ведёт туда. Ниже 13 такой страницы нет, и строки нет тоже: язык там системный.
 */
@Composable
private fun languageRow(): Array<@Composable () -> Unit> {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return emptyArray()
    val context = androidx.compose.ui.platform.LocalContext.current
    return arrayOf({
        Link(Icons.Outlined.Language, R.string.settings_language) {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    })
}

@Composable
private fun AboutRow(title: Int, value: String) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        trailingContent = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AboutLink(title: Int, address: String, url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(address) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable {
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        },
    )
}

/** Три нажатия подряд на значок — скрытый вход в «Бету» (`onTapGesture(count: 3)` на iOS). Одно и два ничего не делают. */
private fun Modifier.tripleTap(action: () -> Unit): Modifier = this.pointerInput(Unit) {
    var taps = 0
    var last = 0L
    detectTapGestures {
        val now = System.currentTimeMillis()
        taps = if (now - last <= TRIPLE_TAP_WINDOW_MS) taps + 1 else 1
        last = now
        if (taps >= 3) {
            taps = 0
            action()
        }
    }
}

private const val TRIPLE_TAP_WINDOW_MS = 500L
