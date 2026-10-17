package moscow.swag.iskra.core.support

/**
 * Журнал самого приложения — не то же, что журнал SDK ([SdkLogging]). Порт `Log.swift`.
 *
 * Он есть потому, что у подписанной сборки на телефоне нет отладчика: ошибка, пойманная и
 * превращённая в предложение для человека, иначе не оставляет следа. Предложение — для человека,
 * запись здесь — для следующего часа отладки.
 *
 * **Сюда не попадает** содержимое сообщений, токены, пароли, имена файлов, которые выбрал человек,
 * и OAuth callback URL (в нём код авторизации, равный по ценности токену). Ошибки и состояния. Если
 * значение нельзя показать постороннему, читающему logcat, его здесь нет.
 */
object Log {
    /** Тег logcat у всего приложения: `adb logcat -s Iskra:*`. Подсистема — в начале строки. */
    const val TAG = "Iskra"

    enum class Category { SESSION, PUSH, BRIDGE, TIMELINE, MEDIA, ROOMS, UPDATE, PROBE }

    /** Куда пишется. На устройстве — logcat; в JVM-тестах android.util.Log нет, и там — stderr. */
    fun interface Sink {
        fun write(priority: Int, category: Category, message: String)
    }

    @Volatile
    var sink: Sink = Sink { priority, category, message ->
        try {
            android.util.Log.println(priority, TAG, "[${category.name.lowercase()}] $message")
        } catch (_: RuntimeException) {
            System.err.println("$TAG [${category.name.lowercase()}] $message")
        } catch (_: NoClassDefFoundError) {
            System.err.println("$TAG [${category.name.lowercase()}] $message")
        }
    }

    fun info(category: Category, message: String) = sink.write(android.util.Log.INFO, category, message)
    fun warn(category: Category, message: String) = sink.write(android.util.Log.WARN, category, message)
    fun error(category: Category, message: String) = sink.write(android.util.Log.ERROR, category, message)

    /**
     * Что можно записать об ошибке: её вид, и ничего из того, что она несёт.
     *
     * `toString()` ошибки — это две разные ошибки в одном написании. У `ClientException.Generic` SDK
     * в сообщении — слова из ответа сервера; у `FileNotFoundException` — путь к файлу человека, а файл
     * называется `паспорт_скан.pdf`. Поэтому записывается только имя класса (`ClientException.Generic`,
     * `FileNotFoundException`) — ответ на вопрос «на каком шаге сломалось», ради которого строка и пишется.
     */
    fun describe(error: Throwable): String {
        val type = error.javaClass
        val enclosing = type.enclosingClass
        return if (enclosing != null && enclosing.isAssignableFrom(type)) {
            "${enclosing.simpleName}.${type.simpleName}"
        } else {
            type.simpleName.ifEmpty { type.name }
        }
    }
}
