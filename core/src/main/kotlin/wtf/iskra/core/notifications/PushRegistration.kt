package wtf.iskra.core.notifications

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/**
 * Куда пушу ехать: ключ устройства у транспорта и шлюз, который его понимает.
 *
 * - FCM (`gms`): [pushkey] — токен FCM, [gateway] — наш Sygnal ([PushRegistration.GATEWAY]): только он
 *   держит ключ Firebase и говорит с Google.
 * - UnifiedPush (`foss`): [pushkey] — адрес endpoint'а у дистрибьютора, [gateway] — Matrix-шлюз самого
 *   дистрибьютора (`…/_matrix/push/v1/notify`, у ntfy есть свой). Sygnal в этом пути не участвует.
 *
 * Не секрет, но и не пишется в лог: он называет *это* устройство, а лог вставляют в отчёты об ошибках.
 */
data class PushTarget(val pushkey: String, val gateway: String) {
    override fun toString() = "PushTarget(…)"
}

/**
 * Сказать серверу, куда слать пуши. Порт `PushRegistration.swift`.
 *
 * `format = EVENT_ID_ONLY` — не оптимизация, а запрет шлюзу (и, значит, Google или дистрибьютору)
 * нести хоть слово сообщения. Слова собираются на устройстве, которому позволено их читать: см.
 * [PushDecoder].
 */
internal class PushRegistration(private val client: Client, private val accountId: String) {
    /**
     * Регистрирует устройство на сервере. Ошибки только в лог: пуши, которые не включились, — не повод
     * останавливать вход.
     */
    suspend fun register(target: PushTarget, deviceName: String, language: String) {
        try {
            withTimeLimit(TIMEOUT) {
                client.setPusher(
                    identifiers = PusherIdentifiers(pushkey = target.pushkey, appId = APP_ID),
                    kind = PusherKind.Http(
                        HttpPusherData(
                            url = target.gateway,
                            // Ни слова сообщения, никогда. См. выше.
                            format = PushFormat.EVENT_ID_ONLY,
                            defaultPayload = defaultPayload(accountId),
                        ),
                    ),
                    appDisplayName = APP_DISPLAY_NAME,
                    deviceDisplayName = deviceName,
                    profileTag = null,
                    // На каком языке *сервер* напишет свою заглушку, если до расшифровки не дойдёт.
                    lang = language,
                    // `true`, и причина — второй аккаунт. Сервер различает pusher'ы по app_id, pushkey
                    // *и пользователю*; `false` добавляет удаление чужих pusher'ов с тем же app_id и
                    // pushkey — а у двух аккаунтов на одном телефоне и сервере они общие, и вход во
                    // второй молча выключал пуши первому.
                    append = true,
                )
            }
            Log.info(Log.Category.PUSH, "the server took our push target")
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.error(Log.Category.PUSH, "the server would not take our push target: ${Log.describe(error)}")
        }
    }

    /**
     * Сказать серверу больше сюда не слать. На выходе, **до** закрытия сессии: запросу она нужна. Иначе
     * сервер продолжает пушить аккаунт на устройство, из которого вышли, — и тот, кто ушёл, получает
     * «Новое сообщение» о чатах, которые уже не открыть.
     *
     * Под теми же десятью секундами, что `logout()`: зависший сервер не держит выход.
     */
    suspend fun unregister(target: PushTarget) {
        try {
            withTimeLimit(TIMEOUT) {
                client.deletePusher(PusherIdentifiers(pushkey = target.pushkey, appId = APP_ID))
            }
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.error(Log.Category.PUSH, "the server would not forget our push target: ${Log.describe(error)}")
        }
    }

    companion object {
        /**
         * Под каким именем шлюз знает приложение: вход в `sygnal.yaml` и строка для Matrix. Решение
         * автора (PROMPT 10.2): `wtf.iskra-android`, в обоих flavor'ах и обеих сборках.
         *
         * Это **не** `applicationId`. В `applicationId` дефис запрещён (каждый сегмент — идентификатор
         * Java), поэтому пакет — `wtf.iskra`, а имя для шлюза живёт здесь. Разведения sandbox/production,
         * как у APNs, у FCM нет: один проект Firebase обслуживает и `wtf.iskra`, и `wtf.iskra.dev`.
         */
        const val APP_ID = "wtf.iskra-android"

        /** Наш Sygnal (`docs/PUSH-GATEWAY.md`). Не настройка: приложение, где шлюз можно вписать, — приложение, где в это можно уговорить. */
        const val GATEWAY = "https://push.iskra.wtf/_matrix/push/v1/notify"

        /**
         * Ключ рядом с остальным в пуше, называющий аккаунт. Пуш говорит, какая комната и какое событие,
         * но не *чьё*, а два человека на одном телефоне могут быть в одной комнате. Значение — случайный
         * ID сессии, никогда не Matrix ID: ни шлюз, ни Google не узнают, кто здесь вошёл.
         */
        const val ACCOUNT_KEY = "iskra_account"

        private const val APP_DISPLAY_NAME = "Iskra"
        private val TIMEOUT = 10.seconds

        /**
         * `default_payload` pusher'а: шлюз передаёт его как есть с каждым пушем. На iOS здесь ещё `aps`
         * с заглушкой — без него iOS не будит расширение; Android будит приложение data-сообщением сам,
         * и заглушку пишет уже на устройстве. Поэтому — только аккаунт.
         */
        fun defaultPayload(accountId: String): String =
            JsonObject(mapOf(ACCOUNT_KEY to JsonPrimitive(accountId))).toString()
    }
}
