package wtf.iskra.core.support

import android.content.Context

/**
 * Строки ресурсов для тех мест `:core`, где слово рождается вне экрана: «Фото» в строке списка,
 * «Аня теперь в чате» в ленте. На iOS это `String(core:)`.
 *
 * Интерфейс, а не `Context`, ради тестов: JVM-тест отдаёт сюда имя ресурса и проверяет, *какое*
 * слово выбрано, не поднимая Android.
 */
interface Strings {
    operator fun get(id: Int): String

    fun format(id: Int, vararg args: Any): String = String.format(get(id), *args)

    /** Множественное число по правилам языка (CLDR), а не «1 сообщений». */
    fun plural(id: Int, count: Int, vararg args: Any): String

    companion object {
        fun of(context: Context): Strings {
            // Контекст приложения: строки живут дольше экрана, а локаль приложения
            // (`localeConfig`) у него та же.
            val app = context.applicationContext
            return object : Strings {
                override fun get(id: Int) = app.getString(id)
                override fun format(id: Int, vararg args: Any) = app.getString(id, *args)
                override fun plural(id: Int, count: Int, vararg args: Any) = app.resources.getQuantityString(id, count, *args)
            }
        }
    }
}
