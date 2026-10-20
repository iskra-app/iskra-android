package wtf.iskra

import android.app.Application
import android.os.Build
import wtf.iskra.core.update.InstalledBuild
import wtf.iskra.features.AppGraph
import wtf.iskra.features.AppGraphOwner

/**
 * Объект-граф приложения. DI-фреймворка нет, как и на iOS: зависимости передаются через
 * конструкторы, а собираются в [AppGraph] (docs/PROMPT.md, 2.2). Логики в :app нет — она в :features.
 */
class IskraApplication : Application(), AppGraphOwner {
    override val graph: AppGraph by lazy {
        AppGraph(
            context = this,
            appVersion = BuildConfig.VERSION_NAME,
            pushTransport = FlavorPush,
            notificationIcon = R.drawable.ic_launcher_monochrome,
            installed = InstalledBuild(
                checksEnabled = BuildConfig.UPDATE_CHECK,
                flavor = BuildConfig.FLAVOR,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                sdkInt = Build.VERSION.SDK_INT,
            ),
        )
    }
}
