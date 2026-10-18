package wtf.iskra.features

import android.content.Context
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
class AppGraph(context: Context, appVersion: String) {
    val environment = AppEnvironment(
        filesDir = context.filesDir,
        cacheDir = context.cacheDir,
        processName = context.packageName,
    )
    val secrets = KeystoreSecrets(environment.secretsDir)
    val settings = AppSettings(context)

    init {
        // Открытые прошлым запуском файлы — открытый текст на диске; чужое приложение их давно прочло.
        wtf.iskra.features.support.FileOpener.sweep(context)
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
}
