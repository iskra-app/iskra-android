package wtf.iskra.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import wtf.iskra.core.media.MediaLoader
import wtf.iskra.core.rooms.RoomListStore
import wtf.iskra.core.support.Strings
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

    /** Список чатов и цикл синхронизации под ним. Запускает его тот, кто показывает список. */
    val rooms = RoomListStore(client, strings, scope)

    private val strings = strings

    /** Поиск по своим сообщениям — по локальному индексу, на сервер не уходит. */
    val search = wtf.iskra.core.search.SearchStore(client, scope, strings)

    /** Как человека зовут и его картинка — для шапки настроек. Читается [loadProfile]. */
    data class Profile(val displayName: String?, val avatarUrl: String?)

    private val mutableProfile = kotlinx.coroutines.flow.MutableStateFlow(Profile(null, null))
    val profile: kotlinx.coroutines.flow.StateFlow<Profile> = mutableProfile

    /** Спросить профиль у сервера (или у кэша SDK). Без сети — остаётся, каким был. */
    suspend fun loadProfile() {
        val name = runCatching { wtf.iskra.core.support.withTimeLimit(PROFILE_LIMIT) { client.displayName() } }.getOrNull()
        val avatar = runCatching { wtf.iskra.core.support.withTimeLimit(PROFILE_LIMIT) { client.avatarUrl() } }.getOrNull()
        mutableProfile.value = Profile(name ?: mutableProfile.value.displayName, avatar ?: mutableProfile.value.avatarUrl)
    }

    /**
     * Переписка комнаты — для экрана, который её показывает. Свой хендл комнаты на каждую ленту:
     * закрывает его [TimelineStore.close], когда экран уходит. Null для комнаты, которой клиент не знает.
     */
    fun timeline(roomId: String): TimelineStore? {
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
        return TimelineStore(
            room = room,
            roomId = roomId,
            ownUserId = userId,
            strings = strings,
            scope = scope,
            onDraftChanged = { rooms.noteDraft(roomId, it) },
            onOwnMessagesRead = { rooms.noteRead(roomId, it) },
        )
    }

    @Volatile
    private var closed = false

    init {
        if (!isDemo) authentication.sessionOpened(sessionId)
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
        scope.cancel()
        if (!isDemo) authentication.sessionClosed(sessionId)
        client.close()
    }

    /** Выход: сервер забывает устройство (если ответит), локально исчезает всё этого аккаунта. */
    suspend fun signOut() {
        authentication.signOut(this)
    }

    private companion object {
        val PROFILE_LIMIT = kotlin.time.Duration.parse("15s")
    }
}
