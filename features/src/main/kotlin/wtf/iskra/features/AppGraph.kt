package wtf.iskra.features

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import wtf.iskra.features.signin.MockSignIn
import wtf.iskra.core.security.KeystoreSecrets
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.AppSettings
import wtf.iskra.core.support.DeviceDescription
import wtf.iskra.core.support.SdkLogging
import wtf.iskra.core.support.Strings

/**
 * Граф объектов приложения. DI-фреймворка нет, как и на iOS: всё собирается здесь, один раз на
 * процесс, и передаётся через конструкторы (docs/PROMPT.md, 2.2). Строит его `Application`.
 */
class AppGraph(context: Context, private val appVersion: String) {
    private val context: Context = context.applicationContext

    val environment = AppEnvironment(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        processName = context.packageName,
    )
    val secrets = KeystoreSecrets(environment.secretsDir)
    val settings = AppSettings(context)

    init {
        // Открытые и игравшие прошлым запуском файлы — открытый текст на диске, и он больше никому не нужен.
        wtf.iskra.features.support.FileOpener.sweepOnLaunch(context)
    }
    val sessionStore = SessionStore(secrets)
    val authentication = AuthenticationService(
        environment = environment,
        sessionStore = sessionStore,
        deviceName = context.getString(wtf.iskra.core.R.string.device_android),
        userAgent = DeviceDescription.userAgent(appVersion),
        strings = Strings.of(context),
    )

    init {
        SdkLogging.start(environment.logsDir)
    }

    /**
     * Корень приложения для этого запуска. [mockSignIn] — слова флага демо-входа (`MockSignIn`), null —
     * обычный запуск: тогда аккаунт устройства и, по флагу [demo] или «Бете», демо-сервер.
     */
    fun rootModel(scope: CoroutineScope, demo: Boolean, mockSignIn: String?): RootModel {
        if (mockSignIn == null) return RootModel(authentication, scope, demo, settings)
        val words = MockSignIn.words(mockSignIn)
        return RootModel(
            MockSignIn.authentication(context, appVersion),
            scope,
            settings = settings,
            signInAddress = { MockSignIn.start(words) },
        )
    }
}
