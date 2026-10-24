package wtf.iskra.features.signin

import android.content.Context
import wtf.iskra.core.mock.demoServers
import wtf.iskra.core.security.InMemorySecrets
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.session.KnownDevices
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.DeviceDescription
import wtf.iskra.core.support.Strings
import java.io.File

/**
 * Демо-вход: экраны входа и регистрации против сервера внутри приложения (`SignInMockServer`). Порт
 * `MockSignIn.swift`. Только в debug ([isAvailable]), как `#if DEBUG` на iOS: сервер лежит в
 * `core/src/debug`.
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

    /** Есть ли демо-вход в этой сборке: в release флаг и пункт в «Бете» ничего не делают. */
    val isAvailable: Boolean get() = demoServers != null

    fun words(extra: String): Set<String> = extra.split(' ', ',').filter { it.isNotBlank() }.toSet()

    @Volatile
    private var service: AuthenticationService? = null

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
            // Какие сеансы аккаунта видели — тоже в памяти, как его секреты: следующий запуск без флага найдёт настоящее, каким оно было.
            knownDevices = KnownDevices(InMemoryKnown()),
        ).also { service = it }
    }

    /**
     * Поднимает сервер — один раз — и возвращает адрес для поля входа. Null, если листенер не
     * поднялся или сборка release: экран входа откроется пустым, а не указывая на порт, где никого нет.
     */
    fun start(words: Collection<String> = emptySet()): String? = demoServers?.startSignIn(words)

    private const val SCRATCH = "IskraMockServer"
}

/** Память `KnownDevices` для демо-входа: живёт, пока процесс. */
private class InMemoryKnown : KnownDevices.Store {
    private val values = HashMap<String, Set<String>>()

    override fun get(account: String): Set<String>? = values[account]

    override fun put(account: String, fingerprints: Set<String>?) {
        if (fingerprints == null) values.remove(account) else values[account] = fingerprints
    }
}
