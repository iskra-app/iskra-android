package wtf.iskra.core.mock

/**
 * Демо-сервер этого процесса и как в него войти. Порт `DemoBackend` и `DemoListener` из
 * `DemoSession.swift`; сам вход — `AuthenticationService.startDemoSession`.
 *
 * Листенер поднимается один раз и живёт с процессом: он стоит сокет, а новый порт на каждое демо
 * ничего не дал бы. Память сервера живёт столько же — выключить и включить демо в одном запуске
 * значит найти те же разговоры (стор SDK при этом опустошается, и вход начинает с нуля).
 */
object DemoBackend {
    /** ID сеанса, под которым лежит стор демо. Не UUID — чтобы узнавался на диске. */
    const val SESSION_ID = "demo"
    internal const val USERNAME = "sasha"
    internal const val PASSWORD = "demo"
    internal const val DEVICE_NAME = "Iskra (demo)"

    internal val server: MockHomeserver by lazy { MockHomeserver() }
    private val listener by lazy { LoopbackHttpServer { server.handle(it) } }

    /** Адрес демо-сервера на loopback; поднимает листенер при первом вызове. */
    internal fun homeserverUrl(): String = "http://127.0.0.1:${listener.start()}"

    /** Что SDK спросил, а сервер не знает, — первое, куда смотреть, когда экран странен только в демо. */
    val unrecognized: List<String> get() = server.unrecognized
}
