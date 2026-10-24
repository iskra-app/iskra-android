package wtf.iskra.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import wtf.iskra.core.media.MediaLoader
import wtf.iskra.core.notifications.NotificationRulesStore
import wtf.iskra.core.notifications.PushRegistration
import wtf.iskra.core.notifications.PushTarget
import wtf.iskra.core.notifications.ReactionPushRule
import wtf.iskra.core.rooms.IdentityChangesStore
import wtf.iskra.core.rooms.RoomListStore
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import wtf.iskra.core.timeline.ThreadSettings
import wtf.iskra.core.timeline.TimelineStore
import org.matrix.rustcomponents.sdk.Client

/**
 * Вошедший аккаунт. Владеет `Client`, и никто над ним SDK не трогает (docs/PROMPT.md, 3, 4.6).
 *
 * Этап 3: клиент, его закрытие и список чатов. Сторы уровня сессии (верификация, папки, стикеры,
 * presence) придут дальше.
 */
class UserSession internal constructor(
    internal val client: Client,
    private val authentication: AuthenticationService,
    /** Случайный ID, который называет каталоги этого аккаунта, — не Matrix ID. */
    val sessionId: String,
    strings: Strings,
    /** Сеанс на демо-сервере: не трогает настоящий аккаунт и хранилище секретов (`DemoBackend`). */
    val isDemo: Boolean = false,
) {
    val userId: String = client.userId()
    val homeserver: String = client.homeserver()

    /**
     * Поток всех сторов сеанса — главный: их состояние меняется только на нём, а колбэки SDK
     * переносятся туда сразу (docs/PROMPT.md, 4.1). Живёт, пока жив сеанс.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Резервная копия переписки и код восстановления. */
    val recovery = RecoveryStore(client.encryption(), scope)

    /**
     * Подтверждение устройств в обе стороны. С сеансом, а не с экраном: входящий запрос приходит,
     * пока человек читает чат, и должен куда-то попасть.
     */
    val verification = VerificationStore(client, userId, scope)

    /** Картинки: аватарки сейчас, вложения — с лентой. */
    val media = MediaLoader(client)

    /** Прозвища личных чатов этого аккаунта (`LocalNames`). У демо свои, и выход из демо их забывает. */
    val localNames: wtf.iskra.core.rooms.LocalNames.Account? = authentication.localNamesOf(sessionId)

    /** Список чатов и цикл синхронизации под ним. Запускает его тот, кто показывает список. */
    val rooms = RoomListStore(client, strings, scope, localNames, authentication.networkWatch)

    private val strings = strings

    /** Новый разговор: личный чат, комната, вход по адресу, каталог сервера. */
    val conversations = wtf.iskra.core.rooms.Conversations(client, userId, scope)

    /** Правила уведомлений аккаунта — push rules на сервере (экран «Уведомления»). */
    val notificationRules: NotificationRulesStore by lazy { NotificationRulesStore(client) }

    /** Поиск по своим сообщениям — по локальному индексу, на сервер не уходит. */
    val search = wtf.iskra.core.search.SearchStore(client, scope, strings)

    /**
     * Стикеры, которые аккаунт может отправить (`StickerLibrary`): сессии, а не комнаты — свой пак,
     * подписки и недавние лежат в account data и общие для устройств. Пак *комнаты* читается авторизованным
     * HTTP-запросом (на iOS — `authorisedSender`), которого здесь ещё нет: он придёт с маршрутом этапа 9, и
     * до него лист показывает свой пак, подписки и «Искорки», но не пак открытой комнаты.
     */
    val stickers = wtf.iskra.core.stickers.StickerLibrary(
        readAccountData = { type -> client.accountData(type) },
        writeAccountData = { type, content -> client.setAccountData(type, content) },
        readRoomPack = { _, _ -> null },
        upload = { mimeType, bytes -> client.uploadMedia(mimeType, bytes, null) },
    )

    /**
     * Видео, альбомы и большие файлы на пути вверх (`MediaSendJobs`): живёт, пока жив сеанс, — уведомление
     * foreground-сервиса следит за ним, когда экрана чата уже нет.
     */
    val mediaJobs = wtf.iskra.core.media.MediaSendJobs(
        roomFor = { id -> runCatching { client.getRoom(id) }.getOrNull() },
        look = { id ->
            val state = rooms.state.value
            (state.rooms + state.archivedRooms).firstOrNull { it.id == id }
                ?.let { wtf.iskra.core.media.MediaSendJobs.RoomLook(it.name, it.avatarSeed) }
        },
        scope = scope,
    )

    /**
     * Сессии аккаунта для экрана «Устройства». Заново на каждый вход на экран: список — правда сервера в этот момент.
     * Вошли через OAuth — сессии заканчиваются на странице самого сервера (`accountUrl`), и экран её откроет.
     */
    fun devices(): DevicesStore = DevicesStore(accountDevices())

    private fun accountDevices(): AccountDevices {
        val requests = AuthorisedRequests(client)
        val managed = runCatching { client.session().oauthData != null }.getOrDefault(false)
        return AccountDevices(
            userId = userId,
            currentDeviceId = client.deviceId(),
            isManagedByServer = managed,
            send = requests::send,
            serverPage = { deviceId ->
                val action = if (deviceId != null) {
                    org.matrix.rustcomponents.sdk.AccountManagementAction.DeviceDelete(deviceId)
                } else {
                    org.matrix.rustcomponents.sdk.AccountManagementAction.DevicesList
                }
                runCatching { withTimeLimit(PROFILE_LIMIT) { client.accountUrl(action) } }.getOrNull()
            },
        )
    }

    /**
     * Баннер «новый вход» над списком чатов (`NewSignInsStore`). Null — не для демо: оно не аккаунт этого устройства, и спрашивать о его сеансах нечего.
     * Один на сеанс: помнит, что уже показывал.
     */
    val newSignIns: NewSignInsStore? by lazy {
        val known = authentication.knownDevices
        if (isDemo || known == null) null else NewSignInsStore(accountDevices(), sessionId, known)
    }

    /**
     * Что приложение держит на устройстве, для экрана «Хранилище». Свой на каждый вход на экран. После очистки клиент этого сеанса кончен
     * ([wtf.iskra.core.storage.StorageStore.clearCache]), и приложение обязано открыть сеанс заново (`RootModel.reopenAfterClearing`).
     */
    fun storage(): wtf.iskra.core.storage.StorageStore = wtf.iskra.core.storage.StorageStore(
        client,
        syncService = {
            rooms.runningSyncService ?: runCatching { withTimeLimit(PROFILE_LIMIT) { client.syncService().withOfflineMode().finish() } }.getOrNull()
        },
        locations = if (isDemo) emptyMap() else authentication.storageLocations(sessionId),
    )

    /** Заблокированные (`ignore` Matrix): подписка живёт с сеансом, экран только читает. */
    val blocked = BlockStore(client, scope)

    /**
     * Сообщает серверу, в сети ли этот аккаунт, — или, когда в «Конфиденциальности» скрыто «был(а)», что нет, чем бы он ни занимался.
     *
     * В биндингах вызова присутствия нет, поэтому это `PUT /presence/{userId}/status` самого приложения, тем же путём, что и всё прочее
     * ([AuthorisedRequests]). Говорится на старте, на каждом возвращении на передний план — сервер, что считает sync за «в сети», уже
     * вернул статус, — и ещё раз, когда переключатель сдвинулся. Сервер с выключенным присутствием ответит 403 или 404, а прятать там нечего,
     * поэтому ответ не смотрят.
     */
    suspend fun applyPresence(privacy: PrivacySettings) {
        if (isDemo || closed) return
        val body = """{"presence":"${if (privacy.hidesPresence) "offline" else "online"}"}"""
        runCatching { AuthorisedRequests(client).send(HomeserverRequest("PUT", "/_matrix/client/v3/presence/${pathSegment(userId)}/status", body)) }
    }

    /** Что сервер говорит о том, где человек. Null — сказать нечего; [PresenceDeclined] — сервер молчит, спрашивать дальше незачем. */
    private suspend fun presenceOf(userId: String): Presence? {
        val response = AuthorisedRequests(client).send(HomeserverRequest("GET", "/_matrix/client/v3/presence/${pathSegment(userId)}/status"))
        if (response.status != 200) throw PresenceDeclined(response.status)
        return Presence.parse(response.body)?.takeIf { it.isWorthShowing }
    }

    /** Matrix ID в пути запроса: `@` и `:` остаются, `/` и всё прочее — нет. */
    private fun pathSegment(id: String): String = buildString {
        for (char in id) {
            if (char.isLetterOrDigit() || char in "-._~@:!$'()*,=") append(char) else append(java.net.URLEncoder.encode(char.toString(), "UTF-8"))
        }
    }

    /** Папки чатов: правила в account data, общие для устройств аккаунта (`ChatFolders`). */
    val folders = wtf.iskra.core.rooms.ChatFolders(
        readAccountData = { type -> client.accountData(type) },
        writeAccountData = { type, content -> client.setAccountData(type, content) },
    )

    /** Картинка по `mxc://` — стикеру в зашифрованной комнате (`TimelineStore.sendSticker`). */
    private val downloadMedia: suspend (String) -> ByteArray = { url ->
        val source = org.matrix.rustcomponents.sdk.MediaSource.fromUrl(url)
        try {
            client.getMediaContent(source)
        } finally {
            source.destroy()
        }
    }

    /** Как человека зовут и его картинка — для шапки настроек. Читается [loadProfile]. */
    data class Profile(val displayName: String?, val avatarUrl: String?)

    private val mutableProfile = kotlinx.coroutines.flow.MutableStateFlow(Profile(null, null))
    val profile: kotlinx.coroutines.flow.StateFlow<Profile> = mutableProfile

    /** Спросить профиль у сервера (или у кэша SDK). Без сети — остаётся, каким был. */
    suspend fun loadProfile() {
        val name = runCatching { withTimeLimit(PROFILE_LIMIT) { client.displayName() } }.getOrNull()
        val avatar = runCatching { withTimeLimit(PROFILE_LIMIT) { client.avatarUrl() } }.getOrNull()
        mutableProfile.value = Profile(name ?: mutableProfile.value.displayName, avatar ?: mutableProfile.value.avatarUrl)
        rememberProfile()
    }

    /**
     * Новое имя профиля. Пустое — не имя, а стёртое поле, которое оставили: его не отправляют. Сразу
     * показывается новое; сервер отказал — старое возвращается, а отказ отдаётся экрану (null — вышло).
     *
     * Сбой возвращается, а не кладётся в поле сеанса — как `throws(AccountActionFailure)` на iOS: сеанс
     * живёт всё время работы приложения, а алерт настроек — нет.
     */
    suspend fun rename(name: String): AccountActionFailure? {
        val trimmed = name.trim()
        val previous = mutableProfile.value
        if (trimmed.isEmpty() || trimmed == previous.displayName) return null
        mutableProfile.value = previous.copy(displayName = trimmed)
        return try {
            withTimeLimit(PROFILE_LIMIT) { client.setDisplayName(trimmed) }
            rememberProfile()
            null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "the name would not change: ${Log.describe(error)}")
            if (mutableProfile.value.displayName == trimmed) mutableProfile.value = mutableProfile.value.copy(displayName = previous.displayName)
            AccountActionFailure.NAME
        }
    }

    /**
     * Новая картинка профиля — байты JPEG, уже обрезанные в квадрат без метаданных (`AvatarCrop` в
     * `:features`). Байтами, а не путём к файлу: файл пришлось бы потом кому-то удалять.
     */
    suspend fun setPicture(jpeg: ByteArray): AccountActionFailure? = try {
        withTimeLimit(UPLOAD_LIMIT) { client.uploadAvatar("image/jpeg", jpeg) }
        val url = runCatching { withTimeLimit(PROFILE_LIMIT) { client.avatarUrl() } }.getOrNull()
        if (url != null) mutableProfile.value = mutableProfile.value.copy(avatarUrl = url)
        rememberProfile()
        null
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Log.error(Log.Category.SESSION, "the picture would not change: ${Log.describe(error)}")
        AccountActionFailure.PICTURE
    }

    /**
     * [rename], когда спросить уже некого: экран профиля ушёл с полем в руках. Уход — тоже «оставили
     * поле», и имя не должно потеряться вместе с экраном; отказ виден по вернувшемуся старому имени.
     */
    fun renameInBackground(name: String) {
        scope.launch { rename(name) }
    }

    suspend fun removePicture(): AccountActionFailure? {
        val previous = mutableProfile.value.avatarUrl ?: return null
        mutableProfile.value = mutableProfile.value.copy(avatarUrl = null)
        return try {
            withTimeLimit(PROFILE_LIMIT) { client.removeAvatar() }
            rememberProfile()
            null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "the picture would not clear: ${Log.describe(error)}")
            if (mutableProfile.value.avatarUrl == null) mutableProfile.value = mutableProfile.value.copy(avatarUrl = previous)
            AccountActionFailure.PICTURE
        }
    }

    /** Переключателю аккаунтов: закрытый аккаунт спросить не у кого. */
    private fun rememberProfile() {
        if (!isDemo) authentication.rememberProfile(sessionId, mutableProfile.value.displayName, mutableProfile.value.avatarUrl)
    }

    /**
     * Переписка комнаты — для экрана, который её показывает. Свой хендл комнаты на каждую ленту:
     * закрывает его [TimelineStore.close], когда экран уходит. Null для комнаты, которой клиент не знает.
     * [threadSettings] — из `AppSettings`, читается при открытии.
     */
    fun timeline(
        roomId: String,
        threadSettings: () -> ThreadSettings = { ThreadSettings() },
        privacy: () -> PrivacySettings = { PrivacySettings() },
        serviceEvents: () -> wtf.iskra.core.timeline.ServiceEventSettings = { wtf.iskra.core.timeline.ServiceEventSettings() },
    ): TimelineStore? {
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
        return TimelineStore(
            room = room,
            roomId = roomId,
            ownUserId = userId,
            strings = strings,
            scope = scope,
            onDraftChanged = { rooms.noteDraft(roomId, it) },
            onOwnMessagesRead = { rooms.noteRead(roomId, it) },
            threadSettings = threadSettings,
            serviceEvents = serviceEvents,
            localNames = localNames,
            downloadMedia = downloadMedia,
            privacy = privacy,
            askPresence = if (isDemo) null else ::presenceOf,
        )
    }

    /**
     * Тред комнаты своей лентой ([TimelineStore.threadRootId]). Свежий стор при каждом открытии:
     * тред — визит внутри визита, а держат открытой комнату. Ни черновика, ни прочитанного списку
     * чатов не сообщает — список о комнате.
     */
    fun threadTimeline(roomId: String, rootEventId: String): TimelineStore? {
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
        return TimelineStore(
            room = room,
            roomId = roomId,
            ownUserId = userId,
            strings = strings,
            scope = scope,
            threadRootId = rootEventId,
            localNames = localNames,
            downloadMedia = downloadMedia,
        )
    }

    /**
     * Сказать серверу слать пуши этого аккаунта на [target], и включить пуши о реакциях. Зовётся при
     * входе и восстановлении, и когда транспорт выдал новый адрес. Демо — никогда: его сервер живёт в
     * этом же процессе, и пушить ему некуда. [language] — на каком языке сервер напишет свою заглушку.
     */
    suspend fun registerPush(target: PushTarget, language: String) {
        if (isDemo || closed) return
        PushRegistration(client, sessionId).register(target, authentication.deviceName, language)
        ReactionPushRule.enable(client)
    }

    /**
     * Кто в комнате с ключами, которых это устройство не узнаёт, — для экрана, который её показывает;
     * [IdentityChangesStore.stop], когда он уходит. Свои хендлы на каждый стор: два экрана одной комнаты
     * (планшет) подписались бы дважды, и каждый снимал бы предупреждение сам, один из них — поздно.
     */
    fun identityChanges(roomId: String): IdentityChangesStore? {
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
        return IdentityChangesStore(room, client.encryption(), userId, scope)
    }

    /** «О чате» комнаты — для экрана, который её показывает; [RoomDetailsStore.stop], когда он уходит. */
    fun details(roomId: String): wtf.iskra.core.rooms.RoomDetailsStore? {
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
        return wtf.iskra.core.rooms.RoomDetailsStore(room, client, roomId, userId, strings, scope, localNames)
    }

    @Volatile
    private var closed = false

    init {
        if (!isDemo) authentication.sessionOpened(this)
    }

    /**
     * Останавливает всё, что работает над клиентом, — синхронизацию прежде всего, — и закрывает его.
     * Так переключаются аккаунты и так приложение готовится к обновлению.
     */
    suspend fun shutDown() {
        verification.stop()
        recovery.stop()
        rooms.stop()
        close()
    }

    /**
     * Закрывает сеанс, не выходя из аккаунта: хендлы SDK отпускаются, стор закрыт. Хендл — нативный
     * объект: GC о памяти за ним не знает, поэтому закрывается явно. Синхронизацию сначала гасит
     * [shutDown]; здесь — только то, что можно сделать без ожидания.
     */
    fun close() {
        if (closed) return
        closed = true
        mediaJobs.stop()
        blocked.stop()
        scope.cancel()
        // Клиент в аренде (`AuthenticationService.leaseClient`): закроется, когда отдаст и пуш, если тот
        // ещё расшифровывает. У демо аренды нет — он только здесь.
        if (isDemo) client.close() else authentication.sessionClosed(this)
    }

    /** Выход: сервер забывает устройство (если ответит), локально исчезает всё этого аккаунта. */
    suspend fun signOut() {
        authentication.signOut(this)
    }

    private companion object {
        val PROFILE_LIMIT = kotlin.time.Duration.parse("15s")
        /** Картинка до 1024 px — сотни килобайт; на медленной сети это не пятнадцать секунд. */
        val UPLOAD_LIMIT = kotlin.time.Duration.parse("60s")
    }
}
