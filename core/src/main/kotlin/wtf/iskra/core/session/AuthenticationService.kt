package wtf.iskra.core.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wtf.iskra.core.mock.DemoServers
import wtf.iskra.core.mock.demoServers
import wtf.iskra.core.notifications.PushRegistration
import wtf.iskra.core.notifications.PushTargets
import wtf.iskra.core.rooms.LocalNames
import wtf.iskra.core.security.AccountRecord
import wtf.iskra.core.security.SessionDelegate
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.security.StoredSession
import wtf.iskra.core.security.toRustSession
import wtf.iskra.core.security.toStored
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.TimeoutError
import wtf.iskra.core.support.withTimeLimit
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.CrossProcessLockConfig
import org.matrix.rustcomponents.sdk.ErrorKind
import uniffi.matrix_sdk.OAuthAuthorizationData
import org.matrix.rustcomponents.sdk.OAuthConfiguration
import org.matrix.rustcomponents.sdk.OAuthPrompt
import org.matrix.rustcomponents.sdk.RequestConfig
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SsoHandler
import uniffi.matrix_sdk_crypto.CollectStrategy
import uniffi.matrix_sdk_crypto.DecryptionSettings
import uniffi.matrix_sdk_crypto.TrustRequirement
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Ведёт вход и, когда он удался, отдаёт живой сеанс. Порт `AuthenticationService.swift`.
 *
 * Ничто здесь не пишет в лог токен, пароль или callback URL: в callback URL код авторизации, и он
 * стоит столько же, сколько токен, который на него покупают.
 *
 * Здесь же — **единственное** место, где настраивается `ClientBuilder` ([makeClientBuilder]): пути
 * сеанса, зашифрованный стор и индекс, делегат токенов, политика шифрования, sliding sync. Путей
 * сборки несколько (вход, восстановление, позже — пуш), и настройка безопасности не должна быть
 * забыта ни на одном.
 *
 * Мост (docs/PROMPT.md, 9) приходит на этапе 9: тогда [route] начнёт ставить рядом с адресом
 * прокси `LocalConnectProxy` и **отказывать**, если мост указан, а туннель не поднялся. Пока мостов
 * нет, маршрут один — напрямую.
 */
class AuthenticationService(
    private val environment: AppEnvironment,
    private val sessionStore: SessionStore,
    /** Как устройство называется в списке сеансов человека: «Iskra на Android». */
    internal val deviceName: String,
    /** `Iskra/<версия> (<модель>; Android <версия>)` — см. `DeviceDescription`. */
    private val userAgent: String,
    /** Слова, которые рождаются в `:core`: «Фото» в строке списка, «Без названия». */
    private val strings: Strings,
    /** Куда едут пуши этого телефона — чтобы выход снял pusher. Null — пушей нет (демо-вход). */
    private val pushTargets: PushTargets? = null,
    /** Прозвища личных чатов: у каждого аккаунта свои, и выход их забывает. */
    private val localNames: LocalNames? = null,
    /** Сеть устройства: возврат её будит синхронизацию без паузы. Null — не следим (тесты). */
    internal val networkWatch: wtf.iskra.core.support.NetworkWatch? = null,
    /** Какие сеансы аккаунта это устройство уже видело — для баннера «новый вход»; выход забывает. */
    internal val knownDevices: KnownDevices? = null,
    /** Откуда берётся положение для живой геопозиции. Null — датчика нет (тесты, демо). */
    internal val locationSource: wtf.iskra.core.rooms.LocationSource? = null,
) : SignInFlow<UserSession> {
    /** Прозвища аккаунта — для его сеанса и для пуша. */
    /** Где на диске лежит то, что хранит этот сеанс, — для экрана «Хранилище». */
    internal fun storageLocations(sessionId: String): Map<wtf.iskra.core.storage.StorageKind, java.io.File> = mapOf(
        wtf.iskra.core.storage.StorageKind.SESSION to environment.sessionDataDir(sessionId),
        wtf.iskra.core.storage.StorageKind.CACHE to environment.sessionCacheDir(sessionId),
        wtf.iskra.core.storage.StorageKind.SEARCH to environment.searchIndexDir(sessionId),
    )

    /** Запись о идущих трансляциях геопозиции: лежит в каталоге сеанса, и выход уносит её вместе с ним. */
    internal fun liveLocationLedger(sessionId: String): java.io.File = java.io.File(environment.sessionDataDir(sessionId), "iskra-live-location")

    internal fun localNamesOf(sessionId: String): LocalNames.Account? = localNames?.of(sessionId)

    /** Состояние входа меняется только под этим замком — аналог `actor` в iOS. */
    private val mutex = Mutex()

    /** Клиент, с которым идёт вход. Отбрасывается, если попытку бросили. */
    private var pendingClient: Client? = null
    private var pendingSessionId: String? = null
    private var pendingOAuth: OAuthAuthorizationData? = null
    private var pendingSso: SsoHandler? = null

    /** Аккаунты, которые сейчас держит открытыми живой [UserSession]. */
    private val openSessions = mutableMapOf<String, UserSession>()

    internal fun sessionOpened(session: UserSession) = synchronized(openSessions) { openSessions[session.sessionId] = session }

    /** Сеанс закрылся: убрать из открытых и отпустить его аренду клиента. */
    internal fun sessionClosed(session: UserSession) {
        synchronized(openSessions) { if (openSessions[session.sessionId] === session) openSessions.remove(session.sessionId) }
        releaseClient(session.sessionId, session.client)
    }

    private fun isOpen(account: AccountRecord) = synchronized(openSessions) { account.sessionId in openSessions }

    /** Сеанс аккаунта, открытый сейчас в этом процессе, — для пуша, который не должен открывать второй. */
    internal fun openSession(sessionId: String): UserSession? = synchronized(openSessions) { openSessions[sessionId] }

    // ---------------------------------------------------------------------------- клиенты

    /**
     * Один `Client` на стор в процессе — **никогда** два на один SQLite (docs/PROMPT.md, 10.3). Пуш
     * приходит, когда приложение закрыто, и открывает клиент сам; если человек открывает приложение,
     * пока пуш ещё расшифровывает, восстановление должно получить *тот же* клиент, а не собрать второй
     * поверх того же файла. Поэтому клиент — в аренде: берут [leaseClient], отдают [releaseClient], и
     * закрывается он, когда отдал последний.
     */
    private class Lease(val client: Client) {
        var holders = 1
    }

    private val leases = HashMap<String, Lease>()

    /** Сборка клиента под этим замком: двое, пришедшие за одним аккаунтом разом, не соберут два. */
    private val leaseBuild = Mutex()

    /**
     * Клиент аккаунта: уже открытый в процессе или восстановленный заново. Null — аккаунта нет.
     * Каждому успешному вызову — свой [releaseClient].
     */
    internal suspend fun leaseClient(sessionId: String): Client? = leaseBuild.withLock {
        synchronized(leases) {
            leases[sessionId]?.let { lease ->
                lease.holders += 1
                return@withLock lease.client
            }
        }
        val client = restoreClient(sessionId) ?: return@withLock null
        synchronized(leases) { leases[sessionId] = Lease(client) }
        client
    }

    /** Отдаёт аренду. Последний закрывает клиент: хендл — нативный, GC о памяти за ним не знает. */
    internal fun releaseClient(sessionId: String, client: Client) {
        val close = synchronized(leases) {
            val lease = leases[sessionId]
            if (lease == null || lease.client !== client) {
                true
            } else {
                lease.holders -= 1
                if (lease.holders == 0) leases.remove(sessionId)
                lease.holders == 0
            }
        }
        if (close) client.close()
    }

    /** Клиент свежего входа становится арендой с одним держателем — сеансом, который его получит. */
    private fun adoptClient(sessionId: String, client: Client) {
        synchronized(leases) { leases[sessionId] = Lease(client) }
    }

    // ---------------------------------------------------------------------------- аккаунты

    /** Все вошедшие на устройстве. Пусто, если хранилище не читается: для экрана это то же, что никого. */
    fun accounts(): List<AccountRecord> = runCatching { sessionStore.accounts() }.getOrDefault(emptyList())

    fun activeAccountId(): String? = runCatching { sessionStore.activeSessionId() }.getOrNull()

    /** Запоминает имя и картинку аккаунта — переключатель показывает их, когда аккаунт закрыт. */
    fun rememberProfile(sessionId: String, displayName: String?, avatarUrl: String?) {
        runCatching { sessionStore.updateProfile(sessionId, displayName, avatarUrl) }
    }

    /**
     * Выход из аккаунта, который *не* на экране. Выйти может только живой клиент — сервер забывает
     * устройство, — поэтому аккаунт открывается рядом, без синхронизации, ровно на время выхода. Не
     * открылся (нет сети, сервер уже завершил сеанс) — локальное уходит всё равно: ключи, токены и стор
     * и есть то, что здесь значит «выйти» (docs/PROMPT.md, 5.2). Открытый сейчас так не трогается.
     */
    suspend fun signOutInBackground(sessionId: String) {
        if (synchronized(openSessions) { sessionId in openSessions }) return
        val session = try {
            restoreSession(sessionId)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.error(Log.Category.SESSION, "закрытый аккаунт не открылся для выхода: ${Log.describe(error)}")
            null
        }
        if (session != null) signOut(session) else forgetSession(sessionId)
    }

    /** Делает другой аккаунт тем, на котором открывается приложение. Закрывает и восстанавливает — вызывающий. */
    fun setActiveAccount(sessionId: String) {
        runCatching { sessionStore.setActive(sessionId) }
    }

    // ---------------------------------------------------------------------------- восстановление

    /**
     * Возвращает аккаунт, который был на экране (или [sessionId]), если он есть.
     *
     * Ограничено по времени, как всё, чего ждёт человек: это то, что идёт под спиннером запуска, и
     * сервер, который принял соединение и замолчал, держал бы приложение на нём вечно.
     *
     * @throws AuthenticationError.NetworkUnavailable сеанс цел, сети нет — человека не разлогинивать.
     * @throws AuthenticationError.SessionEnded сервер не принимает токены — единственный честный повод
     *   попросить войти снова.
     */
    suspend fun restoreSession(sessionId: String? = null): UserSession? {
        val id = sessionId ?: sessionStore.activeSessionId() ?: return null
        // Уже открытый сеанс того же аккаунта — тот же клиент, а не второй на тот же стор.
        val client = leaseClient(id) ?: return null
        return UserSession(client, this, id, strings)
    }

    /** Собирает клиент заново. Только через [leaseClient]: напрямую — второй клиент на открытый стор. */
    private suspend fun restoreClient(sessionId: String): Client? {
        val stored = sessionStore.loadSession(sessionId) ?: return null

        // `.NATIVE`, а не `.DISCOVER_NATIVE`, как при входе: узнавали тогда, и узнавать снова — ровно
        // то, из-за чего запуск без сети заканчивался экраном входа.
        val builder = try {
            makeClientBuilder(stored.sessionId, SlidingSyncVersionBuilder.NATIVE)
        } catch (error: Exception) {
            throw AuthenticationError.StorageUnavailable(error)
        }
        val route = route(stored.homeserverUrl)

        val client = try {
            withTimeLimit(RESTORE_TIMEOUT) {
                // Обычно ни то, ни другое в сеть не ходит: адрес уже найден и записан, комнаты — в
                // локальном сторе. Ограничение всё равно стоит: вызов, который *не должен* ходить в
                // сеть, не получает права держать экран запуска вечно, если всё-таки пошёл.
                val client = through(route, builder).homeserverUrl(route.homeserver).build()
                client.restoreSession(stored.toRustSession(client.homeserver()))
                client
            }
        } catch (error: Throwable) {
            // От вида ошибки зависит, останется ли у человека аккаунт. Сеанс, который не поднялся,
            // *потому что нет сети*, — не завершённый сеанс: токены на месте, сообщения в сторе.
            if (isNetworkFailure(error)) throw AuthenticationError.NetworkUnavailable()
            if (isDeadToken(error)) throw AuthenticationError.SessionEnded()
            throw error
        }

        // Записать, на чём остановился сборщик, — пока это не порт нашего же листенера.
        runCatching {
            val corrected = client.session().toStored(stored.sessionId)
            if (corrected.homeserverUrl != stored.homeserverUrl) sessionStore.save(corrected)
        }
        return client
    }

    // ---------------------------------------------------------------------------- вход

    /**
     * Наводится на сервер и сообщает, как на него можно войти. [address] — то, что напечатал
     * человек: «matrix.org», «example.com» или полный URL. SDK сам делает поиск по `.well-known`.
     */
    override suspend fun startSignIn(address: String): SignInOptions = mutex.withLock {
        // Что сделала для себя прошлая попытка, ей не пригодится. Ошибиться в адресе и напечатать
        // другой — обычный способ пользоваться этим экраном, а каждый проход чеканит session ID и три
        // каталога. Уходит *вся* попытка, а не только каталоги: иначе неудачный новый поиск оставил бы
        // экран с *прошлыми* вариантами, и «Войти» закончил бы старую попытку со стёртым стором.
        abandonPendingLocked()

        val sessionId = UUID.randomUUID().toString()

        // Два отказа, и это не один отказ: стор может не подняться (нет хранилища, нет диска), сервер
        // может не найтись (опечатка, нет сети). Человеку исправлять только второе.
        val builder = try {
            makeClientBuilder(sessionId, SlidingSyncVersionBuilder.DISCOVER_NATIVE)
        } catch (error: Exception) {
            throw AuthenticationError.StorageUnavailable(error)
        }
        pendingSessionId = sessionId

        val route = route(address)
        val client = try {
            withTimeLimit(LOOKUP_TIMEOUT) {
                through(route, builder).serverNameOrHomeserverUrl(route.homeserver).build()
            }
        } catch (_: TimeoutError) {
            throw AuthenticationError.HomeserverUnreachable()
        } catch (error: Exception) {
            Log.info(Log.Category.SESSION, "сервер не нашёлся: ${Log.describe(error)}")
            throw AuthenticationError.HomeserverNotFound()
        }

        val details = try {
            withTimeLimit(LOOKUP_TIMEOUT) { client.homeserverLoginDetails() }
        } catch (_: Exception) {
            client.close()
            throw AuthenticationError.HomeserverUnreachable()
        }

        pendingClient = client
        val options = details.use {
            SignInOptions(
                supportsOAuth = it.supportsOauthLogin(),
                supportsLegacySso = it.supportsSsoLogin(),
                supportsPassword = it.supportsPasswordLogin(),
                supportsRegistration = it.supportedOauthPrompts().any { prompt -> prompt is OAuthPrompt.Create },
                resolvedHomeserverUrl = it.url(),
            )
        }
        if (!options.canUseSingleSignOn && !options.supportsPassword) throw AuthenticationError.NoSupportedSignInMethod()
        options
    }

    /** Куда отправить браузер для входа. OAuth, если сервер умеет, иначе старый SSO: для человека это одно действие. */
    override suspend fun singleSignOnUrl(): String = mutex.withLock {
        val client = pendingClient ?: throw AuthenticationError.NoSignInInProgress()
        val oauth = client.homeserverLoginDetails().use { it.supportsOauthLogin() }
        if (oauth) {
            oauthUrlLocked(client, prompt = null)
        } else {
            val handler = client.startSsoLogin(environment.oauthRedirectUri, null)
            pendingSso = handler
            handler.url()
        }
    }

    /**
     * Куда отправить браузер, чтобы *завести* аккаунт: тот же путь, `prompt = create`. Обратная
     * дорога та же, и [completeSingleSignOn] не нужно знать, что это было. Старого запасного пути нет
     * и быть не может — регистрация по паролю требует UIA, которого нет в FFI.
     */
    override suspend fun registrationUrl(): String = mutex.withLock {
        val client = pendingClient ?: throw AuthenticationError.NoSignInInProgress()
        oauthUrlLocked(client, OAuthPrompt.Create)
    }

    private suspend fun oauthUrlLocked(client: Client, prompt: OAuthPrompt?): String {
        val data = client.urlForOauth(oauthConfiguration(), prompt, null, null, null)
        pendingOAuth = data
        return data.loginUrl()
    }

    /** Заканчивает вход через браузер, когда тот вернулся к нам. */
    override suspend fun completeSingleSignOn(callbackUrl: String): UserSession = mutex.withLock {
        val client = pendingClient ?: throw AuthenticationError.NoSignInInProgress()
        val sessionId = pendingSessionId ?: throw AuthenticationError.NoSignInInProgress()
        try {
            val sso = pendingSso
            when {
                pendingOAuth != null -> client.loginWithOauthCallback(callbackUrl)
                sso != null -> sso.finish(callbackUrl)
                else -> throw AuthenticationError.NoSignInInProgress()
            }
        } catch (error: AuthenticationError) {
            throw error
        } catch (error: Exception) {
            throw AuthenticationError.Sdk(error)
        }
        persistLocked(client, sessionId)
    }

    /** Для серверов без SSO. */
    override suspend fun signIn(username: String, password: String): UserSession = mutex.withLock {
        val client = pendingClient ?: throw AuthenticationError.NoSignInInProgress()
        val sessionId = pendingSessionId ?: throw AuthenticationError.NoSignInInProgress()
        try {
            client.login(username, password, deviceName, null)
        } catch (error: Exception) {
            throw AuthenticationError.Sdk(error)
        }
        persistLocked(client, sessionId)
    }

    /** Бросает начатый вход и даёт SDK убрать свою половину. */
    override suspend fun cancelSignIn(): Unit = mutex.withLock {
        val client = pendingClient
        val oauth = pendingOAuth
        if (client != null && oauth != null) runCatching { client.abortOauthAuth(oauth) }
        abandonPendingLocked()
    }

    private fun abandonPendingLocked() {
        pendingSessionId?.let(::discardStore)
        pendingClient?.close()
        pendingClient = null
        pendingSessionId = null
        pendingOAuth = null
        pendingSso = null
    }

    /**
     * Каталоги и ключи, которые попытка входа сделала себе и не использовала. Сборщик создаёт их
     * *заранее*, до поиска сервера, поэтому их делает каждая попытка, а нужны они только удачной.
     */
    private fun discardStore(sessionId: String) {
        runCatching { sessionStore.discardSecrets(sessionId) }
        environment.sessionDirs(sessionId).forEach { it.deleteRecursively() }
    }

    /**
     * Все зашифрованные сторы на диске, которые больше никому не принадлежат. Говорить так безопасно
     * только в один момент — при запуске, до любого входа: у входа *в процессе* каталоги на диске уже
     * есть, а записи ещё нет. Поэтому вызывается из восстановления в корне и больше нигде.
     *
     * Хранилище, которое не ответило, — не хранилище, сказавшее «никого»: это единственный случай,
     * где удалить всё значило бы удалить всех. Тогда не трогается ничего.
     */
    fun sweepOrphanedStores() {
        val kept = runCatching { sessionStore.accounts().map { it.sessionId }.toSet() }.getOrNull() ?: return
        for (parent in environment.sessionParents) {
            parent.listFiles()?.filter { it.name !in kept }?.forEach { it.deleteRecursively() }
        }
    }

    // ---------------------------------------------------------------------------- демо

    /** Есть ли в этой сборке демо-сервер: только debug, в release флаг и «Бета» его не включают. */
    val canStartDemo: Boolean get() = demoServers != null

    /**
     * Сеанс на демо-сервере ([DemoServers]; только в debug — в release бросает). Порт `startDemoSession`.
     *
     * Сеанс как любой другой от [UserSession] вверх и нарочно ни на что не похожий внизу, потому что
     * единственное, чего он не должен делать никогда, — трогать настоящий аккаунт: в хранилище секретов
     * ничего не пишется, пароли стора случайные на этот запуск, стор опустошается перед каждым демо, а
     * [sweepOrphanedStores] уберёт его при следующем запуске — хранилище о нём не слышало. Ограничено,
     * как любой вход, хотя в сеть здесь ничего не ходит: зависшее демо висело бы на экране запуска.
     */
    suspend fun startDemoSession(): UserSession {
        val servers = checkNotNull(demoServers) { "демо-сервера в этой сборке нет" }
        val id = DemoServers.DEMO_SESSION_ID
        environment.sessionDirs(id).forEach { it.deleteRecursively() }
        val client = withTimeLimit(DEMO_TIMEOUT) {
            val login = servers.demoLogin()
            val client = makeClientBuilder(id, SlidingSyncVersionBuilder.NATIVE, demo = true)
                .homeserverUrl(login.homeserverUrl)
                .build()
            client.login(login.username, login.password, login.deviceName, null)
            client
        }
        return UserSession(client, this, id, strings, isDemo = true)
    }

    // ---------------------------------------------------------------------------- выход

    /**
     * Сначала просим сервер забыть устройство; даже если не вышло, локально уничтожается всё: забытое
     * сервером устройство — куда меньшая беда, чем ключи на телефоне, с которого человек думает, что
     * вышел. Ограничено: сервер, который молчит, не держит человека вошедшим, пока думает.
     */
    suspend fun signOut(session: UserSession) {
        // Синхронизация — первой: дифф, пришедший после `logout()`, тронул бы стор, который тот удаляет.
        session.verification.stop()
        session.recovery.stop()
        session.rooms.stop()
        if (session.isDemo) {
            // Выход из демо — это выход из демо: хранилище секретов и настоящие аккаунты не трогаются.
            session.close()
            environment.sessionDirs(session.sessionId).forEach { it.deleteRecursively() }
            localNames?.forget(session.sessionId)
            knownDevices?.forget(session.sessionId)
            return
        }
        // Pusher — до `logout()`: запросу нужен живой сеанс, а без него сервер продолжал бы слать пуши
        // аккаунта на телефон, из которого вышли. Свои десять секунд — внутри.
        pushTargets?.current?.value?.let { PushRegistration(session.client, session.sessionId).unregister(it) }
        runCatching { withTimeLimit(LOGOUT_TIMEOUT) { session.client.logout() } }
        session.close()
        forgetSession(session.sessionId)
    }

    /** Забывает сеанс, не спрашивая сервер: для выхода, который совершил не человек здесь. */
    fun forgetSession(sessionId: String) {
        runCatching { sessionStore.remove(sessionId, environment) }
        localNames?.forget(sessionId)
        knownDevices?.forget(sessionId)
    }

    // ---------------------------------------------------------------------------- частное

    private suspend fun persistLocked(client: Client, sessionId: String): UserSession {
        val session = client.session()

        // Тот же человек второй раз. Если он сейчас открыт — это «Добавить аккаунт», направленное на
        // себя: новое устройство выходит обратно на сервере и стор уходит. Если не открыт — уходит
        // старая запись: её не удалось открыть, и войти заново — ровно то, как её чинят.
        val existing = accounts().firstOrNull { it.userId == session.userId }
        if (existing != null && !isOpen(existing)) {
            runCatching { sessionStore.remove(existing.sessionId, environment) }
            localNames?.forget(existing.sessionId)
            knownDevices?.forget(existing.sessionId)
        } else if (existing != null) {
            runCatching { withTimeLimit(LOGOUT_TIMEOUT) { client.logout() } }
            abandonPendingLocked()
            throw AuthenticationError.AlreadySignedIn()
        }

        sessionStore.register(session.toStored(sessionId))
        adoptClient(sessionId, client)
        pendingClient = null
        pendingSessionId = null
        pendingOAuth = null
        pendingSso = null
        return UserSession(client, this, sessionId, strings)
    }

    /** Куда направить клиент: адрес и — с мостом, на этапе 9, — прокси рядом с ним. */
    internal data class ClientRoute(val homeserver: String, val proxy: String? = null)

    private fun route(address: String): ClientRoute = ClientRoute(address)

    private fun through(route: ClientRoute, builder: ClientBuilder): ClientBuilder =
        route.proxy?.let { builder.proxy(it) } ?: builder

    /**
     * Единственное место, где настраивается `ClientBuilder` (docs/PROMPT.md, 4.5).
     *
     * @param slidingSync `DISCOVER_NATIVE` спрашивает незнакомый сервер, умеет ли он native sliding
     *   sync, — через сеть. Это правильный вопрос при входе и неправильный при каждом запуске:
     *   ответ уже записан в сеансе, а спрашивать снова — значит не пускать человека в его переписку,
     *   пока нет сети.
     */
    private fun makeClientBuilder(
        sessionId: String,
        slidingSync: SlidingSyncVersionBuilder,
        /** Демо: пароли стора случайные на один запуск, в хранилище секретов не пишется ничего. */
        demo: Boolean = false,
    ): ClientBuilder {
        val data = environment.sessionDataDir(sessionId)
        val cache = environment.sessionCacheDir(sessionId)
        val search = environment.searchIndexDir(sessionId)
        for (dir in listOf(data, cache, search)) {
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("каталог сеанса не создался")
        }

        // В SQLite-сторе расшифрованные сообщения и ключи Megolm, поэтому он зашифрован паролем,
        // который есть только в хранилище секретов.
        val store = SqliteStoreBuilder(data.absolutePath, cache.absolutePath)
            .passphrase(if (demo) UUID.randomUUID().toString() else sessionStore.storePassphrase(sessionId))

        val builder = ClientBuilder()
            .sqliteStore(store)
            // Что сервер пишет в списке устройств. Для входа через браузер — единственный рычаг.
            .userAgent(userAgent)
        // Единственное, что держит записанные токены правдой дольше часа. См. `SessionDelegate`. У демо
        // токенов, которые стоило бы записывать, нет, и писать в хранилище секретов ему нечего.
        return (if (demo) builder else builder.setSessionDelegate(SessionDelegate(sessionStore, sessionId)))
            // Бэкап ключей комнат и cross-signing — с самого начала. В SDK оба по умолчанию выключены,
            // и тогда потерянный телефон — это потерянная навсегда история. Код восстановления остаётся
            // ручным: это единственный секрет, который может хранить только человек.
            .autoEnableBackups(true)
            .autoEnableCrossSigning(true)
            // Кому достаются ключи к написанному и чьи сообщения читаются — см. [EncryptionPolicy].
            .roomKeyRecipientStrategy(EncryptionPolicy.recipients)
            .decryptionSettings(EncryptionPolicy.decryption)
            // Один процесс по умолчанию, но SDK всё равно держит межпроцессную блокировку стора:
            // если обработку пушей когда-нибудь вынесут в отдельный процесс, токены не разойдутся. Демо
            // — один процесс и без делегата токенов, а межпроцессная блокировка без делегата не строится.
            .crossProcessLockConfig(
                if (demo) CrossProcessLockConfig.SingleProcess else CrossProcessLockConfig.MultiProcess(environment.processName),
            )
            // Поисковый индекс — вторая копия каждого прочитанного сообщения, и он шифруется так же.
            .withSearchIndexStore(
                search.absolutePath,
                if (demo) UUID.randomUUID().toString() else sessionStore.searchIndexPassword(sessionId),
            )
            .slidingSyncVersionBuilder(slidingSync)
            // ClientTuning: три повтора на «слишком много запросов», как в iOS; треды на клиенте.
            .requestConfig(RequestConfig(retryLimit = 3uL, timeout = null, maxConcurrentRequests = null, maxRetryTime = null))
            .threadsEnabled(enabled = true, threadSubscriptions = false)
    }

    /**
     * Что Искра говорит серверу о себе при входе. `clientUri` несущий: сервер с MAS выводит из этого
     * домена единственную схему возврата, которую примет. См. [AppEnvironment.OAUTH_CLIENT_URI].
     */
    private fun oauthConfiguration() = OAuthConfiguration(
        clientName = environment.clientName,
        redirectUri = environment.oauthRedirectUri,
        clientUri = AppEnvironment.OAUTH_CLIENT_URI,
        logoUri = null,
        tosUri = null,
        policyUri = null,
        staticRegistrations = emptyMap(),
    )

    companion object {
        /** Достаточно для `.well-known` по медленной мобильной сети и мало настолько, чтобы человек ещё верил, что ему отвечают. */
        private val LOOKUP_TIMEOUT = 30.seconds

        /** Для медленной сети и большого стора; сервер, который не ответит никогда, не держит запуск. */
        private val RESTORE_TIMEOUT = 20.seconds

        /** Медленный сервер успевает; молчащий не держит человека вошедшим. */
        private val LOGOUT_TIMEOUT = 10.seconds

        /** Демо-вход: в сеть не ходит, но зависшее демо не должно висеть на экране запуска вечно. */
        private val DEMO_TIMEOUT = 20.seconds

        /**
         * Виновата ли сеть, а не сеанс. `ClientBuildException` называет сетевые случаи прямо; у
         * `ClientException` сетевого случая нет вовсе, и `Generic` намеренно *не* считается офлайном:
         * гадать в эту сторону — держать человека на «нет соединения», когда его на самом деле вывели.
         */
        internal fun isNetworkFailure(error: Throwable): Boolean = when (error) {
            is TimeoutError -> true
            is ClientBuildException.ServerUnreachable,
            is ClientBuildException.WellKnownLookupFailed,
            is ClientBuildException.SlidingSyncVersion -> true
            else -> false
        }

        /** Слово сервера, что токены негодны: `M_UNKNOWN_TOKEN`. */
        internal fun isDeadToken(error: Throwable): Boolean =
            error is ClientException.MatrixApi && error.kind is ErrorKind.UnknownToken
    }
}

/**
 * Кому достаются ключи к тому, что пишет этот аккаунт, и чьи сообщения он читает. Порт
 * `EncryptionPolicy.swift`; решено с автором 2026-09-30, мимоходом не меняется (docs/PROMPT.md, 8.4).
 *
 * Ключи — всем устройствам, **кроме** случая, когда *проверенный* человек больше не сходится: тогда
 * сообщение ждёт, пузырь говорит почему, и «Отправить всё равно» — решение человека. Более строгое
 * `IDENTITY_BASED_STRATEGY` отказывает всем, кто на старых клиентах.
 */
object EncryptionPolicy {
    val recipients = CollectStrategy.ERROR_ON_VERIFIED_USER_PROBLEM
    val decryption = DecryptionSettings(senderDeviceTrustRequirement = TrustRequirement.UNTRUSTED)
}
