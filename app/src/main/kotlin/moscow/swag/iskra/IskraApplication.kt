package moscow.swag.iskra

import android.app.Application

/**
 * Объект-граф приложения. DI-фреймворка нет, как и на iOS: зависимости передаются через
 * конструкторы, а собираются здесь (docs/PROMPT.md, 2.2). Логики в :app нет — она в :features.
 */
class IskraApplication : Application()
