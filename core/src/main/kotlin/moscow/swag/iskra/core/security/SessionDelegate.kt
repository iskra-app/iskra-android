package moscow.swag.iskra.core.security

import moscow.swag.iskra.core.support.Log
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion

/**
 * Держит записанные токены в согласии с теми, которыми SDK пользуется на самом деле. Порт
 * `SessionDelegate.swift`.
 *
 * SDK обновляет токены сам и держит новую пару в памяти. Без делегата на диске остаётся пара со
 * входа, а refresh-токен одноразовый — и следующий холодный старт предъявляет мёртвый токен:
 * человека «выкинуло» ночью, ни за что. Через этот делегат SDK пишет каждую новую пару сам.
 *
 * - Вызывается **синхронно, из потока Rust**, посреди обновления токена: состояния нет, ждать
 *   нечего.
 * - **Один делегат на клиент**, знающий свой session ID с рождения. Общий, перенастраиваемый перед
 *   сборкой, писал бы токены одного аккаунта в запись другого (docs/PROMPT.md, 5.2). И session ID
 *   переживает круг: в `Session` SDK его нет, а без него сохранённая запись потеряла бы свой стор.
 */
class SessionDelegate(
    private val sessionStore: SessionStore,
    val sessionId: String,
) : ClientSessionDelegate {

    override fun retrieveSessionFromKeychain(userId: String): Session {
        val stored = sessionStore.loadSession(sessionId) ?: throw IllegalStateException("nobody signed in")
        // С несколькими аккаунтами «чьи-то токены» — не обязательно токены того, кого просят.
        check(stored.userId == userId) { "wrong user" }
        return stored.toRustSession()
    }

    override fun saveSessionInKeychain(session: Session) {
        try {
            sessionStore.save(session.toStored(sessionId))
        } catch (error: Exception) {
            // Сделать здесь нечего, но не легшее обновление — это ровно то, что завтра будет выглядеть
            // как выход без причины. Сказать вслух, один раз.
            Log.error(Log.Category.SESSION, "обновлённый токен не лёг в хранилище: ${Log.describe(error)}")
        }
    }
}

internal fun Session.toStored(sessionId: String) = StoredSession(
    sessionId = sessionId,
    userId = userId,
    deviceId = deviceId,
    homeserverUrl = homeserverUrl,
    accessToken = accessToken,
    refreshToken = refreshToken,
    oauthData = oauthData,
)

internal fun StoredSession.toRustSession(homeserver: String = homeserverUrl) = Session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    userId = userId,
    deviceId = deviceId,
    homeserverUrl = homeserver,
    oauthData = oauthData,
    slidingSyncVersion = SlidingSyncVersion.NATIVE,
)
