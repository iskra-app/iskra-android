package moscow.swag.iskra.core.session

import org.matrix.rustcomponents.sdk.Client

/**
 * Вошедший аккаунт. Владеет `Client`, и никто над ним SDK не трогает (docs/PROMPT.md, 3, 4.6).
 *
 * Пока — этап 2: клиент и его закрытие. Синхронизация, список комнат, сторы уровня сессии
 * (верификация, папки, стикеры, presence) придут с этапом 3 и дальше.
 */
class UserSession internal constructor(
    internal val client: Client,
    private val authentication: AuthenticationService,
    /** Случайный ID, который называет каталоги этого аккаунта, — не Matrix ID. */
    val sessionId: String,
) {
    val userId: String = client.userId()
    val homeserver: String = client.homeserver()

    @Volatile
    private var closed = false

    init {
        authentication.sessionOpened(sessionId)
    }

    /**
     * Закрывает сеанс, не выходя из аккаунта: хендлы SDK отпускаются, стор закрыт. Так переключаются
     * аккаунты и так приложение готовится к обновлению. Хендл — нативный объект: GC о памяти за ним
     * не знает, поэтому закрывается явно.
     */
    fun close() {
        if (closed) return
        closed = true
        authentication.sessionClosed(sessionId)
        client.close()
    }

    /** Выход: сервер забывает устройство (если ответит), локально исчезает всё этого аккаунта. */
    suspend fun signOut() {
        authentication.signOut(this)
    }
}
