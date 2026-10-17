package moscow.swag.iskra.features

import moscow.swag.iskra.core.security.StorageCheck
import moscow.swag.iskra.core.session.AuthenticationError
import moscow.swag.iskra.core.support.Log

/**
 * Отладочные пробы (docs/PROMPT.md, 17): то, что видно только на устройстве, проверяется на нём.
 * Запускаются extras в intent'е:
 *
 * ```
 * adb shell am start -n moscow.swag.iskra.dev/moscow.swag.iskra.MainActivity --es probe signin --es address matrix.org
 * adb shell am start -n moscow.swag.iskra.dev/moscow.swag.iskra.MainActivity --es probe verify-storage
 * adb logcat -s Iskra:*
 * ```
 *
 * Каждая проба печатает, где остановилась, и зовёт **тот же код**, что кнопка в интерфейсе.
 */
object Probes {
    suspend fun run(probe: String, extras: Map<String, String>, graph: AppGraph) {
        Log.info(Log.Category.PROBE, "проба $probe")
        when (probe) {
            "verify-storage" -> {
                val result = StorageCheck.run(graph.secrets)
                if (result.ok) Log.info(Log.Category.PROBE, result.summary) else Log.error(Log.Category.PROBE, result.summary)
            }

            "signin" -> {
                val address = extras["address"] ?: return Log.error(Log.Category.PROBE, "нужен --es address <сервер>")
                try {
                    val options = graph.authentication.startSignIn(address)
                    Log.info(Log.Category.PROBE, "сервер найден: $options")
                } catch (error: AuthenticationError) {
                    Log.error(Log.Category.PROBE, "остановились на поиске сервера: ${Log.describe(error)}")
                } finally {
                    graph.authentication.cancelSignIn()
                }
            }

            else -> Log.error(Log.Category.PROBE, "не знаю пробы $probe")
        }
        Log.info(Log.Category.PROBE, "проба $probe закончена")
    }
}
