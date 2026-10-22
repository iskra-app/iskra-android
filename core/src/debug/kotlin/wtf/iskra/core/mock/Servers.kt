package wtf.iskra.core.mock

import wtf.iskra.core.support.Log

/** Debug: демо-серверы есть (`DemoServers`). В release на этом месте — `null`. */
val demoServers: DemoServers? = DebugDemoServers

private object DebugDemoServers : DemoServers {
    override fun demoLogin() = DemoServers.Login(
        homeserverUrl = DemoBackend.homeserverUrl(),
        username = DemoBackend.USERNAME,
        password = DemoBackend.PASSWORD,
        deviceName = DemoBackend.DEVICE_NAME,
    )

    private var signIn: SignInMockServer? = null

    @Synchronized
    override fun startSignIn(words: Collection<String>): String? {
        signIn?.let { return it.start() }
        return try {
            val started = SignInMockServer(signInConfiguration(words))
            val address = started.start()
            signIn = started
            Log.info(Log.Category.SESSION, "демо-вход: $address — alice / password")
            address
        } catch (error: Exception) {
            Log.error(Log.Category.SESSION, "демо-вход не поднялся: ${Log.describe(error)}")
            null
        }
    }
}

/** Какой сервер демо-входа: по словам флага запуска (`MockSignIn`). */
internal fun signInConfiguration(words: Collection<String>): SignInMockServer.Configuration {
    var configuration = SignInMockServer.Configuration(accounts = SIGN_IN_ACCOUNTS)
    if ("password" in words) configuration = configuration.copy(offersOAuth = false, offersRegistration = false)
    if ("sso" in words) {
        configuration = configuration.copy(offersPassword = false, offersOAuth = false, offersRegistration = false, offersLegacySso = true)
    }
    if ("closed" in words) configuration = configuration.copy(offersRegistration = false)
    if ("slow" in words) configuration = configuration.copy(latencyMillis = SLOW_MS)
    return configuration
}

private val SIGN_IN_ACCOUNTS = mapOf("alice" to "password", "bob" to "password")
private const val SLOW_MS = 2_000L
