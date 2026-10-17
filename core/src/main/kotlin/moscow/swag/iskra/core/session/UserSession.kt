package moscow.swag.iskra.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moscow.swag.iskra.core.media.MediaLoader
import moscow.swag.iskra.core.rooms.RoomListStore
import moscow.swag.iskra.core.support.Strings
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

    /** Картинки: аватарки сейчас, вложения — с лентой. */
    val media = MediaLoader(client)

    /** Список чатов и цикл синхронизации под ним. Запускает его тот, кто показывает список. */
    val rooms = RoomListStore(client, strings, scope)

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
}
