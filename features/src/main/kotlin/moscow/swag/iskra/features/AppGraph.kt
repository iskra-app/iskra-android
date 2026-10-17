package moscow.swag.iskra.features

import android.content.Context
import moscow.swag.iskra.core.security.KeystoreSecrets
import moscow.swag.iskra.core.security.SessionStore
import moscow.swag.iskra.core.session.AuthenticationService
import moscow.swag.iskra.core.support.AppEnvironment
import moscow.swag.iskra.core.support.DeviceDescription
import moscow.swag.iskra.core.support.SdkLogging
import moscow.swag.iskra.core.support.Strings

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
    val sessionStore = SessionStore(secrets)
    val authentication = AuthenticationService(
        environment = environment,
        sessionStore = sessionStore,
        deviceName = context.getString(moscow.swag.iskra.core.R.string.device_android),
        userAgent = DeviceDescription.userAgent(appVersion),
        strings = Strings.of(context),
    )

    init {
        SdkLogging.start(environment.logsDir)
    }
}
