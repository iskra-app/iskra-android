package wtf.iskra.features.signin

import android.content.Context
import wtf.iskra.core.mock.SignInMockServer
import wtf.iskra.core.security.InMemorySecrets
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.DeviceDescription
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import java.io.File

/**
 * Демо-вход: экраны входа и регистрации против сервера внутри приложения ([SignInMockServer]). Порт
 * `MockSignIn.swift`.
 *
 * Чтобы работать над этими экранами — и над несколькими аккаунтами — без аккаунта, который не жалко,
 * без сети и без мнения настоящего сервера о том, сколько аккаунтов человеку можно завести за день.
 * Между экраном и сервером всё настоящее: `SignInModel`, `AuthenticationService`, SDK, браузер.
 *
 * Слова после флага выбирают, какой это сервер, и сочетаются:
 *
 * ```sh
 * ./android.sh -- --es mock_signin ""            # OIDC с регистрацией и пароль за ним
 * ./android.sh -- --es mock_signin password      # только пароль: без браузера, без «Создать аккаунт»
 * ./android.sh -- --es mock_signin sso           # старый m.login.sso, и больше ничего
 * ./android.sh -- --es mock_signin closed        # OIDC, но регистрации нет
 * ./android.sh -- --es mock_signin "password slow"   # что угодно из этого, две секунды на запрос
 * ```
 *
 * Аккаунты `alice` и `bob` с паролем `password` есть всегда; регистрация добавляет ещё, пока жив процесс.
 *
 * **Настоящий аккаунт устройства не трогается.** Стор сеанса — во временном каталоге, токены — в
 * памяти ([InMemorySecrets]): следующий запуск без флага найдёт того, кто был, каким он был.
 */
object MockSignIn {
    const val EXTRA = "mock_signin"

    /** Какой сервер: по словам флага запуска. */
    fun configuration(words: Collection<String>): SignInMockServer.Configuration {
        var configuration = SignInMockServer.Configuration(accounts = ACCOUNTS)
        if ("password" in words) configuration = configuration.copy(offersOAuth = false, offersRegistration = false)
        if ("sso" in words) {
            configuration = configuration.copy(offersPassword = false, offersOAuth = false, offersRegistration = false, offersLegacySso = true)
        }
        if ("closed" in words) configuration = configuration.copy(offersRegistration = false)
        if ("slow" in words) configuration = configuration.copy(latencyMillis = SLOW_MS)
        return configuration
    }

    fun words(extra: String): Set<String> = extra.split(' ', ',').filter { it.isNotBlank() }.toSet()

    @Volatile
    private var service: AuthenticationService? = null
    private var server: SignInMockServer? = null

    /**
     * Сервис входа, которым пользуются экраны, пока флаг включён. Один на процесс: выданный им сеанс
     * держит его, и выход должен попасть в те же секреты в памяти, куда записал вход.
     */
    @Synchronized
    fun authentication(context: Context, appVersion: String): AuthenticationService = service ?: run {
        val scratch = File(context.cacheDir, SCRATCH)
        // Прошлый запуск: стор аккаунта на сервере, которого больше нет.
        scratch.deleteRecursively()
        AuthenticationService(
            environment = AppEnvironment(File(scratch, "files"), File(scratch, "cache"), context.packageName),
            sessionStore = SessionStore(InMemorySecrets()),
            deviceName = context.getString(wtf.iskra.core.R.string.device_android),
            userAgent = DeviceDescription.userAgent(appVersion),
            strings = Strings.of(context),
        ).also { service = it }
    }

    /**
     * Поднимает сервер — один раз — и возвращает адрес для поля входа. Null, если листенер не
     * поднялся: экран входа откроется пустым, а не указывая на порт, где никого нет.
     */
    @Synchronized
    fun start(words: Collection<String> = emptySet()): String? {
        server?.let { return it.start() }
        return try {
            val started = SignInMockServer(configuration(words))
            val address = started.start()
            server = started
            Log.info(Log.Category.SESSION, "демо-вход: $address — alice / password")
            address
        } catch (error: Exception) {
            Log.error(Log.Category.SESSION, "демо-вход не поднялся: ${Log.describe(error)}")
            null
        }
    }

    private val ACCOUNTS = mapOf("alice" to "password", "bob" to "password")
    private const val SLOW_MS = 2_000L
    private const val SCRATCH = "IskraMockServer"
}
