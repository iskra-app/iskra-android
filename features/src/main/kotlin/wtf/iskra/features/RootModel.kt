package wtf.iskra.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.security.AccountRecord
import wtf.iskra.core.session.AuthenticationError
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppSettings
import wtf.iskra.core.support.Log
import wtf.iskra.features.push.RootPushes
import wtf.iskra.features.update.Updates
import wtf.iskra.features.signin.SignInModel
import wtf.iskra.features.signin.WebAuthentication

/**
 * Корень приложения: в какой фазе оно сейчас. Порт фаз и `restore()` из `IskraRootView.swift`
 * (docs/PROMPT.md, 15).
 *
 * Восстановление и вход — явные вызовы модели, а не побочный эффект composable, который кто-нибудь
 * разумно удалит (та же ошибка, что опустошила ленты на iOS, раздел 4.2).
 */
class RootModel(
    private val authentication: AuthenticationService,
    private val scope: CoroutineScope,
    /**
     * Открыть демо-сервер вместо настоящего аккаунта (`--mock-backend` на iOS; здесь — extra
     * `mock_backend` в intent запуска). Настоящий аккаунт остаётся нетронутым и вернётся при
     * следующем запуске без флага.
     */
    private val demo: Boolean = false,
    /** Настройки приложения: «Бета → Демо-сервер» решает, с чего начинать, наравне с флагом запуска. */
    val settings: AppSettings? = null,
    /**
     * Демо-вход (`MockSignIn`): поднимает сервер входа и отдаёт его адрес для поля. Null — обычный
     * запуск. С ним [authentication] — сервис демо-входа, и вспоминать некого: его секреты в памяти.
     */
    private val signInAddress: (suspend () -> String?)? = null,
    /** Пуши: регистрация, нажатия, видимый чат. Null — без пушей (демо-вход, тесты). */
    val pushes: RootPushes? = null,
    /** Проверка и установка обновлений. Null — без них (демо-вход, тесты). */
    val updates: Updates? = null,
    /** Список библиотек для «О приложении» (`R.raw.aboutlibraries` из `:app`); 0 — нет. */
    val librariesResource: Int = 0,
) {
    /** Адрес, который экран входа подставляет сам: сервер демо-входа. Пусто — человек вводит свой. */
    private var address = ""

    sealed interface Phase {
        data object Starting : Phase

        /** Никого нет. [endedRemotely] — сеанс завершили где-то ещё, и это нужно *сказать*. */
        data class SignedOut(val endedRemotely: Boolean = false) : Phase

        /**
         * Вошли, а сеанс не поднялся, потому что нет сети. Своя фаза: два способа не получить сеанс
         * при запуске — не одно и то же и не должны выглядеть одинаково. Экран входа здесь сказал бы
         * человеку, что аккаунта больше нет, а это неправда и страшно.
         */
        data object Offline : Phase

        /**
         * Только что вошли, и сеанс уже синхронизируется — а экран спрашивает, что это устройство может
         * прочесть (резервная копия, код, другое устройство). Только после *входа*: при запуске уже
         * вошедшего эту работу делает баннер в списке.
         */
        data class SettingUp(val session: UserSession) : Phase

        data class SignedIn(val session: UserSession) : Phase
    }

    /** Настройка восстановления закончилась — в переписку. */
    fun finishSetup() {
        val session = (mutablePhase.value as? Phase.SettingUp)?.session ?: return
        mutablePhase.value = Phase.SignedIn(session)
    }

    private val mutablePhase = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    init {
        updates?.onInstallFailed = { scope.launch { reopenAfterUpdate() } }
        // Флаг запуска — то же, что переключатель в «Бете»: запоминается. Иначе активити, созданная
        // заново без extras (вернулись «Назад» и открыли снова, система пересоздала после установки),
        // молча открывала вход вместо демо. Выключается там же, в «Бете», или выходом.
        // В release демо нет вовсе (`DemoServers`): ни флаг, ни запомненный переключатель его не открывают.
        val demoAvailable = authentication.canStartDemo
        if (demo && demoAvailable) settings?.setDemoBackend(true)
        if (signInAddress == null && demoAvailable && (demo || settings?.demoBackend?.value == true)) startDemo() else restore()
    }

    /**
     * «Бета → Демо-сервер». Открытый сеанс кладётся (настоящий — закрывается, не выходя из аккаунта;
     * демо — стирается целиком), и открывается другой. Настоящий аккаунт при этом не трогается.
     */
    fun setDemo(on: Boolean) {
        if (on && !authentication.canStartDemo) return
        settings?.setDemoBackend(on)
        val current = sessionNow
        mutablePhase.value = Phase.Starting
        scope.launch {
            if (current != null) {
                if (current.isDemo) current.signOut() else current.shutDown()
            }
            if (on) startDemo().join() else restore().join()
        }
    }

    private fun startDemo() = scope.launch {
        Log.info(Log.Category.SESSION, "открываем демо-сервер")
        try {
            signedIn(authentication.startDemoSession())
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.error(Log.Category.SESSION, "демо не открылось: ${Log.describe(error)}")
            mutablePhase.value = Phase.SignedOut()
        }
    }

    // ---------------------------------------------------------------------------- аккаунты

    /**
     * Аккаунты устройства, как их видит переключатель (`AccountSwitcher` на iOS). Экраны ничего не
     * делают сами — просят модель: переключение закрывает один сеанс и открывает другой, а это
     * дело корня.
     */
    data class Accounts(
        /** Все вошедшие, в порядке добавления, тот, что на экране, — тоже. */
        val all: List<AccountRecord> = emptyList(),
        val activeId: String? = null,
        /** Есть ли здесь между чем переключаться: в демо нет — его сеанс не аккаунт устройства. */
        val isAvailable: Boolean = false,
        /** Из каких сейчас выходят — строка говорит об этом, а не стоит нетронутой десять секунд. */
        val signingOut: Set<String> = emptySet(),
        /** Открыт ли вход для ещё одного аккаунта — поверх приложения. */
        val isAdding: Boolean = false,
    ) {
        /** Все, кроме того, что на экране. */
        val others: List<AccountRecord> get() = all.filter { it.sessionId != activeId }
        val hasSeveral: Boolean get() = all.size > 1
    }

    private val mutableAccounts = MutableStateFlow(Accounts())
    val accounts: StateFlow<Accounts> = mutableAccounts.asStateFlow()

    private fun reloadAccounts() {
        val session = sessionNow
        mutableAccounts.value = mutableAccounts.value.copy(
            all = authentication.accounts(),
            activeId = session?.sessionId,
            isAvailable = session != null && !session.isDemo,
        )
    }

    /**
     * Другой аккаунт — на экран. Открытый *закрывается*, не выходя (как при уходе в демо), и приложение
     * начинается заново на другом: `restore()` открывает его, потому что он теперь активный. Его стор
     * на диске, токены в Keystore — это запуск без запуска, сеть для его чатов не нужна.
     */
    fun switchTo(sessionId: String) {
        val current = sessionNow ?: return
        if (current.sessionId == sessionId || current.isDemo) return
        mutablePhase.value = Phase.Starting
        scope.launch {
            current.shutDown()
            authentication.setActiveAccount(sessionId)
            restore().join()
        }
    }

    /**
     * Нажали уведомление другого аккаунта на этом телефоне — сначала на него; чат откроет экран, когда
     * тот аккаунт будет на нём (запрос ждёт в [RootPushes.router]). Аккаунта уже нет — false: запрос
     * выбрасывается, а не открывает чужой чат в том, что на экране.
     */
    fun switchForNotification(sessionId: String): Boolean {
        if (authentication.accounts().none { it.sessionId == sessionId }) return false
        switchTo(sessionId)
        return true
    }

    /** «Добавить аккаунт»: вход поверх приложения, а открытый аккаунт под ним работает, пока новый не вошёл. */
    fun addAccount() {
        if (sessionNow?.isDemo != false) return
        mutableAccounts.value = mutableAccounts.value.copy(isAdding = true)
    }

    /** Передумали добавлять: начатое входом — бросить, открытое — не трогать. */
    fun cancelAdding() {
        if (!mutableAccounts.value.isAdding) return
        mutableAccounts.value = mutableAccounts.value.copy(isAdding = false)
        scope.launch { authentication.cancelSignIn() }
    }

    /** Модель входа для ещё одного аккаунта: тот же экран, что при первом входе. */
    fun addAccountModel(browser: WebAuthentication): SignInModel<UserSession> =
        SignInModel(authentication, browser, scope, onSignedIn = ::added, address = address)

    /** Вошли ещё в один. Он — на экран; тот, что был, закрыт и остаётся вошедшим, в одно касание. */
    private fun added(session: UserSession) {
        val previous = sessionNow
        mutableAccounts.value = mutableAccounts.value.copy(isAdding = false)
        signedIn(session, settingUp = true)
        if (previous != null) scope.launch { previous.shutDown() }
    }

    /** Выход из аккаунта, который не на экране, — тот, что на экране, остаётся где был. */
    fun signOutOther(sessionId: String) {
        val accounts = mutableAccounts.value
        if (sessionId == accounts.activeId || sessionId in accounts.signingOut) return
        mutableAccounts.value = accounts.copy(signingOut = accounts.signingOut + sessionId)
        scope.launch {
            try {
                authentication.signOutInBackground(sessionId)
                pushes?.signedOut(sessionId, anyoneLeft = authentication.accounts().isNotEmpty())
            } finally {
                mutableAccounts.value = mutableAccounts.value.copy(signingOut = mutableAccounts.value.signingOut - sessionId)
                reloadAccounts()
            }
        }
    }

    /** Модель экрана входа: живёт столько, сколько фаза «никого нет». */
    fun signInModel(browser: WebAuthentication): SignInModel<UserSession> =
        SignInModel(authentication, browser, scope, onSignedIn = { signedIn(it, settingUp = true) }, address = address)

    /** На экране ли приложение. Вне экрана синхронизация спит — сообщения приносят пуши (этап 7). */
    private var isForeground = true

    fun setForeground(foreground: Boolean) {
        if (isForeground == foreground) return
        isForeground = foreground
        reportVisibleRoom()
        val session = sessionNow ?: return
        // Обновления — при выходе на передний план, не на холодном старте под сплэшем; раз в сутки решает
        // сама проверка. Демо — не проверяет: это не запуск человека.
        if (foreground && !session.isDemo) updates?.let { scope.launch { it.checker.checkInBackground() } }
        scope.launch { if (foreground) session.rooms.resume() else session.rooms.pause() }
    }

    /** Чат, открытый на экране сейчас, — от экрана. Null — список, настройки, ничего. */
    private var openRoomId: String? = null

    /** Экран открыл чат или ушёл из него. Уведомления открытого чата не показываются и снимаются. */
    fun roomOnScreen(roomId: String?) {
        openRoomId = roomId
        reportVisibleRoom()
    }

    /** Видимый чат — только пока приложение на экране: в фоне каждое сообщение снова новость. */
    private fun reportVisibleRoom() {
        val pushes = pushes ?: return
        val session = sessionNow
        val room = openRoomId.takeIf { isForeground && session != null }
        pushes.visible(room, session?.sessionId?.takeIf { session.isDemo.not() })
    }

    /** Подписка сеанса на адрес пушей; у следующего сеанса — своя. */
    private var pushRegistration: kotlinx.coroutines.Job? = null

    /**
     * Сеанс открыт — и синхронизация запускается *здесь*, явным вызовом, а не `LaunchedEffect` на
     * экране списка, который кто-нибудь разумно уберёт (docs/PROMPT.md, 4.2).
     */
    private fun signedIn(session: UserSession, settingUp: Boolean = false) {
        // Демо не о чем спрашивать: его ключи живут один запуск.
        mutablePhase.value = if (settingUp && !session.isDemo) Phase.SettingUp(session) else Phase.SignedIn(session)
        reloadAccounts()
        pushRegistration?.cancel()
        pushRegistration = null
        val pushes = pushes
        if (pushes != null && !session.isDemo) {
            pushes.signedIn()
            // Каждый новый адрес транспорта — серверу. Первый может прийти раньше сеанса или позже.
            pushRegistration = scope.launch {
                pushes.target.collect { target -> if (target != null) session.registerPush(target, pushes.language()) }
            }
        }
        scope.launch {
            // Имя и картинка — в запись аккаунта: переключатель покажет их, когда он закрыт.
            if (!session.isDemo) {
                launch {
                    session.loadProfile()
                    reloadAccounts()
                }
            }
            // Подтверждение и резервная копия слушают с первого мгновения сеанса: запрос «это ты?»
            // с другого устройства может прийти до того, как человек откроет настройки.
            launch { session.verification.listen() }
            session.recovery.load()
            try {
                session.rooms.start()
                if (!isForeground) session.rooms.pause()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Стор уже записал, что не так; список останется в загрузке до повтора.
                Log.error(Log.Category.ROOMS, "синхронизация не запустилась: ${Log.describe(error)}")
            }
        }
    }

    /**
     * Ставим обновление: сеанс закрывается (стор закрыт, очередь отправки сохранена), иначе процесс убьют
     * посреди записи в SQLite. После обновления — обычный `restore()` нового процесса.
     */
    suspend fun closeForUpdate() {
        val session = sessionNow ?: return
        mutablePhase.value = Phase.Starting
        session.shutDown()
    }

    /** Установка не случилась — открыть то, что закрыли ради неё. */
    fun reopenAfterUpdate() {
        if (mutablePhase.value != Phase.Starting) return
        restore()
    }

    /** «Повторить» на экране без сети: та же попытка, никто не разлогинен. */
    fun retry() {
        if (mutablePhase.value != Phase.Offline) return
        mutablePhase.value = Phase.Starting
        restore()
    }

    /** Открытый сеанс — в переписке или ещё на настройке восстановления. */
    private val sessionNow: UserSession?
        get() = when (val phase = mutablePhase.value) {
            is Phase.SignedIn -> phase.session
            is Phase.SettingUp -> phase.session
            else -> null
        }

    fun signOut() {
        val session = sessionNow ?: return
        // Выход из демо — это выход из демо: следующий запуск открывает настоящий аккаунт.
        if (session.isDemo) settings?.setDemoBackend(false)
        scope.launch {
            pushRegistration?.cancel()
            session.signOut()
            if (!session.isDemo) pushes?.signedOut(session.sessionId, anyoneLeft = authentication.accounts().isNotEmpty())
            mutablePhase.value = Phase.SignedOut()
            reloadAccounts()
            // Если на устройстве есть ещё аккаунты — открыть следующий, а не показывать вход.
            if (authentication.accounts().isNotEmpty()) {
                mutablePhase.value = Phase.Starting
                restore()
            }
        }
    }

    /**
     * Возвращает вошедшего, если он есть, прежде чем что-то показывать. Аккаунт, бывший на экране
     * последним, потом остальные по порядку: один не открывшийся аккаунт — не повод прятать
     * остальных за экраном входа.
     */
    private fun restore() = scope.launch {
        // Под демо-входом сервер должен подняться прежде экрана входа.
        if (signInAddress != null && address.isEmpty()) {
            address = withContext(Dispatchers.IO) { signInAddress.invoke() }.orEmpty()
        }
        // Чьё-то брошенное: сторы входов, которые не закончились, и сеансов, которые заменили. Они
        // читаемы — пароль стабилен, — так что это не мусор, а копия переписки, на которую ничто не
        // указывает. Безопасно сказать это можно только сейчас.
        authentication.sweepOrphanedStores()

        val accounts = authentication.accounts()
        val active = authentication.activeAccountId()
        val order = listOfNotNull(active) + accounts.map { it.sessionId }.filter { it != active }
        var ended = false

        for (id in order) {
            try {
                val session = authentication.restoreSession(id) ?: continue
                authentication.setActiveAccount(id)
                signedIn(session)
                return@launch
            } catch (_: AuthenticationError.NetworkUnavailable) {
                // Никогда не экран входа. Сеть одна на все аккаунты, следующего пробовать незачем.
                mutablePhase.value = Phase.Offline
                return@launch
            } catch (_: AuthenticationError.SessionEnded) {
                // Сервер сказал, что аккаунт окончен, — единственный отказ, который это решает.
                ended = true
                authentication.forgetSession(id)
            } catch (error: Exception) {
                // Оставлен, а не забыт: стор, который сегодня не открылся, — не повод его уничтожать.
                // Повторный вход в тот же аккаунт заменяет запись — так она и чинится.
                Log.error(Log.Category.SESSION, "аккаунт не открылся: ${Log.describe(error)}")
            }
        }
        mutablePhase.value = Phase.SignedOut(endedRemotely = ended)
    }
}
