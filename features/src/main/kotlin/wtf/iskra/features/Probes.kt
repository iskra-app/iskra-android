package wtf.iskra.features

import wtf.iskra.core.notifications.PushPayload
import wtf.iskra.core.security.StorageCheck
import wtf.iskra.core.session.AuthenticationError
import wtf.iskra.core.support.Log

/**
 * Отладочные пробы (docs/PROMPT.md, 17): то, что видно только на устройстве, проверяется на нём.
 * Запускаются extras в intent'е:
 *
 * ```
 * adb shell am start -n wtf.iskra.dev/wtf.iskra.MainActivity --es probe signin --es address matrix.org
 * adb shell am start -n wtf.iskra.dev/wtf.iskra.MainActivity --es probe verify-storage
 * adb shell am start -n wtf.iskra.dev/wtf.iskra.MainActivity --es probe push --es room '!id' --es event '\$id'
 * adb shell am start -n wtf.iskra.dev/wtf.iskra.MainActivity --es probe bridge --es npub npub1… --es homeserver matrix.org
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

            // Пуш без транспорта: тот же путь «расшифровать → показать», что у FCM и UnifiedPush, на
            // настоящем событии. ID события — «Показать исходный код» в любом клиенте Matrix.
            "push" -> {
                val room = extras["room"] ?: return Log.error(Log.Category.PROBE, "нужен --es room <!id> --es event <\$id>")
                val event = extras["event"] ?: return Log.error(Log.Category.PROBE, "нужен --es event <\$id>")
                graph.push.handle(PushPayload(room, event, extras["account"] ?: graph.authentication.activeAccountId(), extras["unread"]?.toIntOrNull()))
            }

            "push-sample" -> graph.push.sample()

            // Что нашлось бы и какой файл был бы выбран — без скачивания. `--es base http://…/dir` — свой
            // сервер вместо GitHub, только в debug; тогда «Настройки» покажут найденное и дадут поставить.
            "update" -> {
                val base = extras["base"]
                try {
                    val offer = if (base != null) {
                        graph.updates.probe(wtf.iskra.core.update.GitHubUpdateSource(base))
                            ?: return Log.error(Log.Category.PROBE, "свой адрес релиза — только в debug")
                    } else {
                        graph.updates.checker.checkNow()
                    }
                    Log.info(Log.Category.PROBE, "обновление: $offer")
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.error(Log.Category.PROBE, "релиз не прочитался: ${Log.describe(error)}")
                }
            }

            // Один настоящий запрос через мост и диагноз — та же функция, что кнопка «Проверить мост» в
            // «Подключении» (docs/PROMPT.md, 9.8). Без `npub` — мост из настроек; без `homeserver` — matrix.org.
            "bridge" -> {
                val settings = graph.tunnelSettings
                val address = extras["npub"] ?: settings.address.value
                val key = wtf.iskra.core.transport.TunnelSettings.bridgeKeyOf(address)
                    ?: return Log.error(Log.Category.PROBE, "это не адрес моста: нужен --es npub npub1…")
                val homeserver = wtf.iskra.core.transport.TunnelSettings.upstreamUrl(extras["homeserver"] ?: "matrix.org")!!
                val relays = extras["relays"]?.let(wtf.iskra.core.transport.TunnelSettings::parseRelays)?.ifEmpty { null } ?: settings.relays
                Log.info(Log.Category.PROBE, "мост ${relays.size} relay'ями, сервер $homeserver; ключ устройства ${graph.tunnelIdentity.npub()}")
                val outcome = wtf.iskra.core.transport.BridgeCheck.run(key, relays, homeserver, graph.tunnelIdentity.secretKey())
                if (outcome is wtf.iskra.core.transport.BridgeCheck.Outcome.Reachable) {
                    Log.info(Log.Category.PROBE, "мост: $outcome")
                } else {
                    Log.error(Log.Category.PROBE, "мост: $outcome")
                }
            }

            else -> Log.error(Log.Category.PROBE, "не знаю пробы $probe")
        }
        Log.info(Log.Category.PROBE, "проба $probe закончена")
    }
}
