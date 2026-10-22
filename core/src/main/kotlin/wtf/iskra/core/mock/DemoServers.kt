package wtf.iskra.core.mock

/**
 * Серверы внутри приложения — демо ([demoServers] → `DemoBackend`) и демо-вход (`SignInMockServer`).
 * Их код лежит в `src/debug`, как на iOS за `#if DEBUG`: в release его нет вовсе, [demoServers] там
 * null, и ни экстры запуска `mock_backend` / `mock_signin`, ни пункт в «Бете» ничего не включают.
 */
interface DemoServers {
    /** Как войти в демо: адрес поднимается при первом вызове и живёт с процессом. */
    class Login(val homeserverUrl: String, val username: String, val password: String, val deviceName: String)

    fun demoLogin(): Login

    /**
     * Поднимает сервер демо-входа — один раз на процесс — и возвращает адрес для поля входа. [words] —
     * слова флага `mock_signin` (`MockSignIn`), читаются только при первом подъёме. Null — листенер
     * не поднялся.
     */
    fun startSignIn(words: Collection<String>): String?

    companion object {
        /** ID сеанса, под которым лежит стор демо. Не UUID — чтобы узнавался на диске. */
        const val DEMO_SESSION_ID = "demo"
    }
}
