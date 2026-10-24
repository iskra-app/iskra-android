package wtf.iskra.core.support

import java.io.File

/**
 * Где приложение держит своё и как оно представляется миру. Порт `AppEnvironment.swift`.
 *
 * На iOS здесь App Group и общая Keychain-группа; на Android их нет и они не нужны: пуши, виджеты
 * и share target работают в том же приложении (docs/PROMPT.md, 5.1). Поэтому всё — внутри файлов
 * приложения в credential-encrypted storage, и бэкап выключен целиком.
 */
class AppEnvironment(
    /** `Context.filesDir`. */
    private val filesDir: File,
    /** `Context.cacheDir`. */
    private val cacheDir: File,
    /** Имя процесса для межпроцессной блокировки стора SDK — пакет приложения. */
    val processName: String,
    /**
     * Куда сервер возвращает браузер после входа. Схема — `clientUri` наоборот: сервер с MAS
     * принимает только её (см. `oauthClientUri`). Тот же адрес, что на iOS.
     */
    val oauthRedirectUri: String = OAUTH_REDIRECT_URI,
    /** Как приложение называется на экране согласия сервера и в списке сеансов человека. */
    val clientName: String = "Iskra App",
) {
    /** Зашифрованный стор SDK (SQLite) одного сеанса. */
    fun sessionDataDir(sessionId: String) = File(filesDir, "sessions/$sessionId")

    /** Кэш SDK одного сеанса: то, что можно потерять. */
    fun sessionCacheDir(sessionId: String) = File(cacheDir, "sessions/$sessionId")

    /** Зашифрованный поисковый индекс одного сеанса — вторая копия каждого сообщения. */
    fun searchIndexDir(sessionId: String) = File(filesDir, "search/$sessionId")

    /** Все три каталога сеанса, в одном месте, чтобы удаление не забыло ни один. */
    fun sessionDirs(sessionId: String) = listOf(sessionDataDir(sessionId), sessionCacheDir(sessionId), searchIndexDir(sessionId))

    /** Родительские каталоги, в которых живут сеансы, — для уборки сирот при запуске. */
    val sessionParents: List<File> get() = listOf(File(filesDir, "sessions"), File(cacheDir, "sessions"), File(filesDir, "search"))

    /** Зашифрованные записи секретов (`KeystoreSecrets`). */
    val secretsDir: File get() = File(filesDir, "secrets")

    /**
     * Ключ nostr устройства — обратный адрес туннеля моста. Отдельно от [secretsDir], и это решение:
     * выход опустошает секреты сеансов, и ключ рядом с ними пересоздался бы, а закрытый мост
     * перестал бы пускать устройство — сказать мосту о новом ключе можно только через мост
     * (docs/PROMPT.md, 9.4).
     */
    val transportSecretsDir: File get() = File(filesDir, "transport")

    /** Кольцевые файлы журнала SDK. */
    val logsDir: File get() = File(filesDir, "logs")

    companion object {
        const val OAUTH_REDIRECT_URI = "wtf.iskra:/oauth-callback"

        /**
         * Сервер с Matrix Authentication Service выводит единственную схему возврата, которую примет,
         * из этого домена, развёрнутого задом наперёд: `iskra.wtf` → `wtf.iskra:`.
         * Держать в согласии с [OAUTH_REDIRECT_URI] и intent-filter в манифесте.
         */
        const val OAUTH_CLIENT_URI = "https://iskra.wtf"
    }
}
