package wtf.iskra

import android.app.Application
import wtf.iskra.features.AppGraph
import wtf.iskra.features.AppGraphOwner

/**
 * Объект-граф приложения. DI-фреймворка нет, как и на iOS: зависимости передаются через
 * конструкторы, а собираются в [AppGraph] (docs/PROMPT.md, 2.2). Логики в :app нет — она в :features.
 */
class IskraApplication : Application(), AppGraphOwner {
    override val graph: AppGraph by lazy {
        AppGraph(this, BuildConfig.VERSION_NAME, FlavorPush, R.drawable.ic_launcher_monochrome)
    }
}
