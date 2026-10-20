package wtf.iskra.features

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import wtf.iskra.features.push.Push
import wtf.iskra.features.push.PushRouter
import wtf.iskra.features.push.PushTransport
import wtf.iskra.features.push.RootPushes
import wtf.iskra.features.signin.MockSignIn
import wtf.iskra.features.update.Updates
import wtf.iskra.core.update.GitHubUpdateSource
import wtf.iskra.core.update.InstalledBuild
import wtf.iskra.core.update.UpdateChecker
import wtf.iskra.core.notifications.NotificationActions
import wtf.iskra.core.notifications.PushDecoder
import wtf.iskra.core.notifications.PushTargets
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
class AppGraph(
    context: Context,
    private val appVersion: String,
    /** Как пуши доезжают до телефона — у каждого flavor'а свой (`:app/src/gms`, `:app/src/foss`). */
    pushTransport: PushTransport,
    /** Значок уведомлений в строке состояния — знак Искры, живущий в `:app`. */
    notificationIcon: Int,
    /** Что это за сборка — для проверки обновлений: flavor, versionCode, разрешена ли проверка. */
    installed: InstalledBuild,
    /** Список библиотек, который плагин aboutlibraries кладёт в `:app` (`R.raw.aboutlibraries`). */
    private val librariesResource: Int = 0,
) {
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

    /** Куда едут пуши этого телефона: выдаёт транспорт flavor'а, помнится для выхода. */
    val pushTargets = PushTargets(context)

    val authentication = AuthenticationService(
        environment = environment,
        sessionStore = sessionStore,
        deviceName = context.getString(wtf.iskra.core.R.string.device_android),
        userAgent = DeviceDescription.userAgent(appVersion),
        strings = Strings.of(context),
        pushTargets = pushTargets,
    )

    /** Нажатия на уведомления и чат на экране — один на процесс (см. [PushRouter]). */
    val pushRouter = PushRouter()

    /** Пуш → уведомление. Транспорты flavor'ов зовут [Push.handle]. */
    val push = Push(
        context = context,
        decoder = PushDecoder(authentication, Strings.of(context)),
        actions = NotificationActions(authentication),
        router = pushRouter,
        targets = pushTargets,
        settings = settings,
        transport = pushTransport,
        smallIcon = notificationIcon,
    )

    /** Проверка и установка обновлений (docs/PROMPT.md, 19.1). */
    val updates = Updates(
        context = context,
        checker = UpdateChecker(GitHubUpdateSource(), settings, installed, language = { appLanguage(context) }),
        source = GitHubUpdateSource(),
    )

    init {
        SdkLogging.start(environment.logsDir)
    }

    /**
     * Корень приложения для этого запуска. [mockSignIn] — слова флага демо-входа (`MockSignIn`), null —
     * обычный запуск: тогда аккаунт устройства и, по флагу [demo] или «Бете», демо-сервер.
     */
    fun rootModel(scope: CoroutineScope, demo: Boolean, mockSignIn: String?): RootModel {
        if (mockSignIn == null) {
            return RootModel(authentication, scope, demo, settings, pushes = RootPushes(context, push, pushRouter, pushTargets), updates = updates,
                librariesResource = librariesResource)
        }
        val words = MockSignIn.words(mockSignIn)
        return RootModel(
            MockSignIn.authentication(context, appVersion),
            scope,
            settings = settings,
            signInAddress = { MockSignIn.start(words) },
        )
    }
}

/** Язык приложения (`localeConfig`): на нём «Что нового» и заглушка сервера. */
internal fun appLanguage(context: Context): String =
    context.resources.configuration.locales[0]?.language?.ifEmpty { null } ?: "en"
