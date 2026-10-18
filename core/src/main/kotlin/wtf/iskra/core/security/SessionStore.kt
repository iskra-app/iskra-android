package wtf.iskra.core.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import wtf.iskra.core.support.AppEnvironment
import java.security.SecureRandom
import java.util.Base64

/** Всё, что Искра хранит о вошедшем человеке. Всё это секрет, и всё лежит в [SecretStoring]. */
@Serializable
data class StoredSession(
    /** Называет каталоги сеанса на диске. Случайный: два входа никогда не делят стор. */
    val sessionId: String,
    val userId: String,
    val deviceId: String,
    val homeserverUrl: String,
    val accessToken: String,
    val refreshToken: String?,
    /** Непрозрачное состояние OAuth от SDK: нужно, чтобы потом обновлять токены. */
    val oauthData: String?,
) {
    /** Имя сервера из Matrix ID: `@ann:matrix.org` → `matrix.org`. Последнее средство, если адрес испорчен. */
    val serverName: String? get() = userId.substringAfter(':', "").ifEmpty { null }

    /** Секреты не печатаются даже случайно — в лог, в исключение, в отладчик. */
    override fun toString() = "StoredSession(sessionId=$sessionId, userId=$userId, deviceId=$deviceId)"
}

/**
 * Один аккаунт, как его показывает переключатель. Ничего секретного, но лежит рядом с токенами: список
 * аккаунтов на устройстве — это список людей, которые им пользуются, и в бэкап он не уходит.
 * Имя и картинка — кэш на то время, когда аккаунт закрыт и спросить не у кого.
 */
@Serializable
data class AccountRecord(
    val sessionId: String,
    val userId: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

/**
 * Секреты вошедших аккаунтов. Порт `SessionStore.swift` (без переноса старого формата — на Android
 * его не было).
 *
 * У каждого аккаунта три записи, названные его session ID: токены, пароль стора и пароль поискового
 * индекса, — и одна общая запись `accounts`: кто, в каком порядке и кто на экране.
 *
 * **По аккаунтам, а не три записи на устройство.** С двумя аккаунтами «удалить всё» при выходе
 * первого забирает ключ стора второго — зашифрованная база, которую никто не откроет, у человека,
 * который ничего не сделал (docs/PROMPT.md, 5.2).
 */
class SessionStore(private val secrets: SecretStoring) {
    @Serializable
    private data class Index(val accounts: List<AccountRecord> = emptyList(), val activeSessionId: String? = null)

    /** Все вошедшие, в порядке добавления. */
    fun accounts(): List<AccountRecord> = index().accounts

    /** Аккаунт на экране: последний, на который переключились, или — если его нет — первый. */
    fun activeSessionId(): String? {
        val index = index()
        return index.activeSessionId?.takeIf { id -> index.accounts.any { it.sessionId == id } }
            ?: index.accounts.firstOrNull()?.sessionId
    }

    fun setActive(sessionId: String) = updateIndex { index ->
        if (index.accounts.none { it.sessionId == sessionId }) index else index.copy(activeSessionId = sessionId)
    }

    /**
     * Добавляет только что вошедший аккаунт, сохраняет его токены и ставит его на экран. Токены —
     * первыми: падение между двумя шагами оставит токены, которых никто не перечисляет (ничего не
     * стоит), и никогда — аккаунт в списке, которому нечем войти.
     */
    fun register(session: StoredSession) {
        save(session)
        updateIndex { index ->
            val existing = index.accounts.indexOfFirst { it.sessionId == session.sessionId }
            val accounts = if (existing >= 0) {
                index.accounts.toMutableList().also { it[existing] = it[existing].copy(userId = session.userId) }
            } else {
                index.accounts + AccountRecord(session.sessionId, session.userId)
            }
            index.copy(accounts = accounts, activeSessionId = session.sessionId)
        }
    }

    /** Запоминает, как выглядит аккаунт. Сначала сравнивает: сотое то же имя не переписывает запись. */
    fun updateProfile(sessionId: String, displayName: String?, avatarUrl: String?) {
        val current = accounts().firstOrNull { it.sessionId == sessionId } ?: return
        if (current.displayName == displayName && current.avatarUrl == avatarUrl) return
        updateIndex { index ->
            index.copy(accounts = index.accounts.map {
                if (it.sessionId == sessionId) it.copy(displayName = displayName, avatarUrl = avatarUrl) else it
            })
        }
    }

    /** Токены аккаунта на экране или null, когда никто не вошёл. */
    fun loadSession(): StoredSession? = activeSessionId()?.let(::loadSession)

    fun loadSession(sessionId: String): StoredSession? =
        secrets.read(sessionKey(sessionId))?.let { json.decodeFromString(StoredSession.serializer(), it.decodeToString()) }

    /**
     * Пишет токены одного аккаунта — и ничего больше. Это вызывает обновление токена, синхронно, из
     * потока Rust, поэтому индекс здесь намеренно не трогается: добавляет в список только вход.
     */
    fun save(session: StoredSession) {
        secrets.write(sessionKey(session.sessionId), json.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
    }

    /**
     * Забывает один аккаунт целиком: сначала секреты — падение посередине не оставит на диске
     * зашифрованный стор, ключ от которого у нас ещё есть. Только этот аккаунт: остальные сохраняют
     * всё. Если он был на экране — на экран встаёт следующий.
     */
    fun remove(sessionId: String, environment: AppEnvironment) {
        discardSecrets(sessionId)
        updateIndex { index ->
            val accounts = index.accounts.filter { it.sessionId != sessionId }
            val active = if (index.activeSessionId == sessionId) accounts.firstOrNull()?.sessionId else index.activeSessionId
            Index(accounts, active)
        }
        environment.sessionDirs(sessionId).forEach { it.deleteRecursively() }
    }

    /** Три ключа сеанса, который не будет использован: брошенная попытка входа или уходящий аккаунт. */
    fun discardSecrets(sessionId: String) {
        secrets.remove(sessionKey(sessionId))
        secrets.remove(storePassphraseKey(sessionId))
        secrets.remove(searchIndexKey(sessionId))
    }

    /** Пароль, которым SDK шифрует SQLite-стор этого сеанса. */
    fun storePassphrase(sessionId: String): String = existingOrNewSecret(storePassphraseKey(sessionId))

    /** Пароль, которым SDK шифрует поисковый индекс этого сеанса. */
    fun searchIndexPassword(sessionId: String): String = existingOrNewSecret(searchIndexKey(sessionId))

    private fun existingOrNewSecret(key: String): String {
        secrets.read(key)?.let { return it.decodeToString() }
        val secret = randomSecret()
        secrets.write(key, secret.encodeToByteArray())
        return secret
    }

    private fun index(): Index =
        secrets.read(ACCOUNTS)?.let { json.decodeFromString(Index.serializer(), it.decodeToString()) } ?: Index()

    /** Каждое изменение индекса — прочитать, изменить, записать; два таких вперемешку потеряли бы одно. */
    private fun updateIndex(change: (Index) -> Index) = synchronized(lock) {
        secrets.write(ACCOUNTS, json.encodeToString(Index.serializer(), change(index())).encodeToByteArray())
    }

    companion object {
        private const val ACCOUNTS = "accounts"
        private fun sessionKey(id: String) = "session.$id"
        private fun storePassphraseKey(id: String) = "store-passphrase.$id"
        private fun searchIndexKey(id: String) = "search-index-key.$id"

        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        /** 256 бит из системного CSPRNG. Назван, потому что это параметр безопасности. */
        private const val KEY_BYTES = 32

        fun randomSecret(): String = Base64.getEncoder().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))
    }
}
