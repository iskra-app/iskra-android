package moscow.swag.iskra

import android.app.Application
import moscow.swag.iskra.features.AppGraph

/**
 * Объект-граф приложения. DI-фреймворка нет, как и на iOS: зависимости передаются через
 * конструкторы, а собираются в [AppGraph] (docs/PROMPT.md, 2.2). Логики в :app нет — она в :features.
 */
class IskraApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this, BuildConfig.VERSION_NAME) }
}
