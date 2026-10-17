package moscow.swag.iskra.core.support

import android.content.Context

/**
 * Строки ресурсов для тех мест `:core`, где слово рождается вне экрана: «Фото» в строке списка,
 * «Без названия» вместо имени комнаты. На iOS это `String(core:)`.
 *
 * Интерфейс, а не `Context`, ради тестов: JVM-тест отдаёт сюда имя ресурса и проверяет, *какое*
 * слово выбрано, не поднимая Android.
 */
fun interface Strings {
    operator fun get(id: Int): String

    companion object {
        fun of(context: Context): Strings {
            // Контекст приложения: строки живут дольше экрана, а локаль приложения
            // (`localeConfig`) у него та же.
            val app = context.applicationContext
            return Strings { app.getString(it) }
        }
    }
}
