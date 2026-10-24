package wtf.iskra.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.design.AccentTheme
import wtf.iskra.design.AppearanceMode
import wtf.iskra.design.IskraTheme
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.core.session.UserSession
import wtf.iskra.features.push.NotificationPermission
import wtf.iskra.features.room.RoomScreen
import wtf.iskra.features.recovery.RecoveryBanner
import wtf.iskra.features.recovery.RecoverySetupModel
import wtf.iskra.features.recovery.RecoverySetupScreen
import wtf.iskra.features.recovery.VerificationDialog
import wtf.iskra.features.rooms.RoomListScreen
import wtf.iskra.features.settings.SettingsPage
import wtf.iskra.features.settings.AccountMenu
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import wtf.iskra.features.settings.SettingsScreen
import wtf.iskra.features.support.FaceMode
import wtf.iskra.features.support.LocalFaceMode
import wtf.iskra.features.signin.SignInScreen
import wtf.iskra.features.support.LocalMediaLoader
import wtf.iskra.features.support.LocalAppSettings
import wtf.iskra.features.support.LocalOwnUserId
import wtf.iskra.features.signin.WebAuthentication

/**
 * Корень приложения (`IskraRootView` на iOS). Фазы — из [RootModel]; экраны получают действия от
 * того, кто их открыл, а не закрывают себя сами через глобальный навигатор (docs/PROMPT.md, 15).
 */
@Composable
fun IskraRoot(
    model: RootModel,
    browser: WebAuthentication,
    /** Версия для «О приложении»: `BuildConfig` живёт в `:app`. */
    appVersion: String = "",
    /** Замок приложения от `MainActivity`. Null — без замка (проба). */
    lock: wtf.iskra.features.lock.LockHooks? = null,
) {
    val phase by model.phase.collectAsStateWithLifecycle()
    val accentName by (model.settings?.accent ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    val modeName by (model.settings?.appearance ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsStateWithLifecycle()
    IskraTheme(
        accent = AccentTheme.fromStorage(accentName),
        appearance = AppearanceMode.fromStorage(modeName),
    ) {
        Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(wtf.iskra.features.lock.LocalLock provides lock) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val current = phase) {
                RootModel.Phase.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalAccent.current.control)
                }

                is RootModel.Phase.SignedOut -> {
                    val signIn = remember(current) { model.signInModel(browser) }
                    Column {
                        if (current.endedRemotely) {
                            Notice(R.string.session_ended_title, R.string.session_ended_message)
                        }
                        SignInScreen(signIn)
                    }
                }

                RootModel.Phase.Offline -> Centered {
                    Notice(R.string.session_offline_title, R.string.session_offline_message)
                    PrimaryActionButton(onClick = model::retry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.session_try_again))
                    }
                }

                is RootModel.Phase.SettingUp -> {
                    val setup = remember(current.session) { RecoverySetupModel(current.session) }
                    RecoverySetupScreen(setup, onDone = model::finishSetup)
                }

                is RootModel.Phase.SignedIn -> CompositionLocalProvider(
                    LocalMediaLoader provides current.session.media,
                    LocalOwnUserId provides current.session.userId,
                    LocalAppSettings provides model.settings,
                ) {
                    SignedIn(current.session, model, browser, appVersion)
                }
            }
        }
        }
        // Щит над всем, в том числе над входом: заперто — значит, не видно ничего.
        if (lock != null) {
            val locked by lock.appLock.isLocked.collectAsStateWithLifecycle()
            if (locked) wtf.iskra.features.lock.LockScreen(lock)
        }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Theme.Size.signInColumn).padding(Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun Notice(title: Int, message: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = Theme.Spacing.generous),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/**
 * Вошли: список чатов и открытая переписка поверх него. Навигации-фреймворка нет, как и на iOS:
 * открытая комната — состояние, «назад» — его сброс.
 */
@Composable
private fun SignedIn(session: UserSession, model: RootModel, browser: WebAuthentication, appVersion: String) {
    val accounts by model.accounts.collectAsStateWithLifecycle()
    // «Добавить аккаунт»: вход поверх приложения; открытый аккаунт под ним работает, пока новый не вошёл.
    if (accounts.isAdding) {
        val adding = remember { model.addAccountModel(browser) }
        Dialog(
            onDismissRequest = model::cancelAdding,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    SignInScreen(adding)
                    TextButton(
                        onClick = model::cancelAdding,
                        modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(Theme.Spacing.close),
                    ) { Text(stringResource(R.string.accounts_cancel)) }
                }
            }
        }
    }
    var openRoom by rememberSaveable(session.sessionId) { mutableStateOf<String?>(null) }
    // Найденное поиском: открыть комнату — и на этом сообщении.
    var openEvent by rememberSaveable(session.sessionId) { mutableStateOf<String?>(null) }
    var settingsPage by rememberSaveable(session.sessionId) { mutableStateOf<SettingsPage?>(null) }
    // «Новый чат» поверх списка: открывает комнату и закрывается сам.
    var creating by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    val settings = model.settings
    val creatures by (settings?.creatures ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsStateWithLifecycle()
    val forced by (settings?.forceCreatures ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsStateWithLifecycle()
    val roomId = openRoom
    val timeline = remember(roomId) {
        roomId?.let { id ->
            session.timeline(
                id,
                threadSettings = { settings?.threads?.value ?: wtf.iskra.core.timeline.ThreadSettings() },
                privacy = { settings?.privacy?.value ?: wtf.iskra.core.session.PrivacySettings() },
                serviceEvents = { settings?.serviceEvents?.value ?: wtf.iskra.core.timeline.ServiceEventSettings() },
            )
        }
    }
    // «Поиск» из «О чата»: «О чате» закрывается, и комната открывается сразу в поиске.
    var searchOnOpen by remember(session.sessionId) { mutableStateOf(false) }
    var unlocking by rememberSaveable(session.sessionId) { mutableStateOf(false) }
    // «О чате» открытой комнаты — поверх её переписки, «назад» возвращает в неё.
    var showingDetails by rememberSaveable(session.sessionId, roomId) { mutableStateOf(false) }
    // Стор смены ключей одноразовый — его останавливает экран комнаты, когда уходит, — поэтому на возврате
    // из «О чате» (комната пересоздаётся) нужен новый.
    val identityChanges = remember(roomId, showingDetails) { roomId?.let(session::identityChanges) }
    val details = remember(roomId, showingDetails) { roomId?.takeIf { showingDetails }?.let(session::details) }
    val rooms by session.rooms.state.collectAsStateWithLifecycle()
    val rootScope = androidx.compose.runtime.rememberCoroutineScope()

    // Нажатое уведомление: в *этот* разговор, и один раз. Чужой аккаунт — сначала переключиться на него;
    // запрос подождёт в роутере, пока тот аккаунт не окажется здесь.
    val pushes = model.pushes
    if (pushes != null) {
        val request by pushes.router.request.collectAsStateWithLifecycle()
        LaunchedEffect(request, session.sessionId) {
            val wanted = request ?: return@LaunchedEffect
            val account = wanted.accountId
            if (account != null && account != session.sessionId && !session.isDemo) {
                if (model.switchForNotification(account)) return@LaunchedEffect
            }
            pushes.router.takeRequest()
            settingsPage = null
            openEvent = null
            openRoom = wanted.roomId
        }
        NotificationPermission(session, settings)
        // Какой чат на экране — уведомления о нём не показываются и снимаются, когда его открыли.
        DisposableEffect(roomId, session.sessionId) {
            model.roomOnScreen(roomId)
            onDispose { model.roomOnScreen(null) }
        }
    }

    // Ссылка, открытая снаружи: страница человека или комнаты поверх всего; «назад» — туда, где были.
    var linked by remember(session.sessionId) { mutableStateOf<wtf.iskra.core.rooms.MatrixLink?>(null) }
    model.links?.let { links ->
        val incoming by links.request.collectAsStateWithLifecycle()
        LaunchedEffect(incoming, session.sessionId) {
            val link = links.take() ?: return@LaunchedEffect
            settingsPage = null
            creating = false
            linked = link
        }
    }

    // Поделились из другого приложения: выбрать чат, и он откроется с этим в поле ввода. Раз взятое отдаётся комнате один раз.
    var sharing by remember(session.sessionId) { mutableStateOf<SharedContent?>(null) }
    var shareIntoRoom by remember(session.sessionId) { mutableStateOf<SharedContent?>(null) }
    model.shares?.let { shares ->
        val incoming by shares.request.collectAsStateWithLifecycle()
        LaunchedEffect(incoming, session.sessionId) {
            val wanted = incoming ?: return@LaunchedEffect
            val account = wanted.accountId
            // Direct Share в чат другого аккаунта: сначала переключиться, содержимое подождёт в роутере.
            if (account != null && account != session.sessionId && !session.isDemo) {
                if (model.switchForNotification(account)) return@LaunchedEffect
            }
            val content = shares.take() ?: return@LaunchedEffect
            settingsPage = null
            creating = false
            linked = null
            openEvent = null
            if (content.roomId != null) {
                shareIntoRoom = content
                openRoom = content.roomId
            } else {
                openRoom = null
                sharing = content
            }
        }
    }

    // Точка на шестерёнке: есть версия новее. Не диалог и не уведомление — человек пришёл читать.
    val offer by (model.updates?.checker?.offer ?: remember { kotlinx.coroutines.flow.MutableStateFlow(null) }).collectAsStateWithLifecycle()
    val updateWaiting = offer is wtf.iskra.core.update.UpdateOffer.Available

    CompositionLocalProvider(LocalFaceMode provides FaceMode(creatures, forced)) {
        val page = settingsPage
        when {
            linked != null -> wtf.iskra.features.rooms.MatrixLinkScreen(
                session.conversations,
                linked!!,
                onOpen = {
                    linked = null
                    openEvent = null
                    openRoom = it
                },
                onBack = { linked = null },
                blocked = session.blocked,
            )
            sharing != null -> wtf.iskra.features.rooms.ShareTargetScreen(
                rooms = rooms.rooms,
                onPick = { id ->
                    shareIntoRoom = sharing
                    sharing = null
                    openRoom = id
                },
                onBack = { sharing = null },
            )
            // Баннер «старые сообщения заперты» ведёт сюда: тот же экран, что после входа.
            unlocking -> {
                val setup = remember(session) { RecoverySetupModel(session) }
                RecoverySetupScreen(setup, onDone = { unlocking = false })
            }
            page != null && settings != null -> SettingsScreen(
                session = session,
                settings = settings,
                page = page,
                onNavigate = { settingsPage = it },
                onSignOut = model::signOut,
                onDemo = model::setDemo,
                appVersion = appVersion,
                accounts = accounts,
                onSwitchAccount = { id ->
                    settingsPage = null
                    openRoom = null
                    model.switchTo(id)
                },
                onStorageCleared = model::reopenAfterClearing,
                onAddAccount = model::addAccount,
                onSignOutOther = model::signOutOther,
                browser = browser,
                pushes = model.pushes.takeIf { !session.isDemo },
                // Найденное показывается и в демо; *ходить* за ним демо не ходит (RootModel.setForeground).
                updates = model.updates,
                beforeUpdate = model::closeForUpdate,
                afterFailedUpdate = { model.reopenAfterUpdate() },
                librariesResource = model.librariesResource,
            )
            details != null -> wtf.iskra.features.room.RoomDetailsScreen(
                store = details,
                blocked = session.blocked,
                isOffline = rooms.isUnreachable,
                onBack = { showingDetails = false },
                onSearch = { showingDetails = false; searchOnOpen = true },
                onLeave = {
                    val leaving = details.roomId
                    showingDetails = false
                    openRoom = null
                    rootScope.launch { session.rooms.leave(leaving) }
                },
            )
            timeline != null -> RoomScreen(
                timeline,
                onBack = {
                    openRoom = null
                    openEvent = null
                },
                mediaSettings = { settings?.media?.value ?: wtf.iskra.core.media.MediaSettings.DEFAULT },
                initialJump = openEvent,
                openThread = { root -> roomId?.let { session.threadTimeline(it, root) } },
                onOpenDetails = { showingDetails = true },
                identityChanges = identityChanges,
                stickers = session.stickers,
                mediaJobs = session.mediaJobs,
                blocked = session.blocked,
                share = shareIntoRoom.takeIf { roomId != null },
                onShareTaken = { shareIntoRoom = null },
                isOffline = rooms.isOffline,
                isUpdating = rooms.isUpdating,
                members = session::details.takeIf { !session.isDemo || true },
                onMessagePerson = { person -> rootScope.launch { runCatching { session.conversations.openDirectChat(person) }.onSuccess { openRoom = it } } },
                startSearching = searchOnOpen,
                onSearchStarted = { searchOnOpen = false },
            )
            creating -> wtf.iskra.features.rooms.NewConversationScreen(
                session,
                onOpen = {
                    creating = false
                    openRoom = it
                },
                onClose = { creating = false },
            )
            else -> RoomListScreen(
                session.rooms,
                onOpen = { openRoom = it },
                onNewConversation = { creating = true },
                search = session.search,
                onOpenAt = { room, event ->
                    openEvent = event
                    openRoom = room
                },
                onSettings = { settingsPage = SettingsPage.MAIN },
                settingsDot = updateWaiting,
                folders = session.folders,
                onEditFolders = { settingsPage = SettingsPage.FOLDERS },
                banner = {
                    Column {
                        RecoveryBanner(session) { unlocking = true }
                        session.newSignIns?.let { wtf.iskra.features.rooms.NewSignIns(it) }
                    }
                },
                accountMenu = if (accounts.isAvailable) {
                    { expanded, onDismiss ->
                        AccountMenu(expanded, accounts, onDismiss, onSwitch = model::switchTo, onAdd = model::addAccount)
                    }
                } else {
                    null
                },
            )
        }
        // Своё другое устройство спрашивает «это ты?» — где бы человек ни был.
        if (!unlocking) VerificationDialog(session.verification)
    }
}
