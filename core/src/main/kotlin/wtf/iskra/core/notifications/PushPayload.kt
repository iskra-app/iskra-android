package wtf.iskra.core.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Что пришло в пуше, каким бы транспортом он ни ехал. Слов в нём нет (`event_id_only`): только где
 * событие, чьё оно и сколько у аккаунта непрочитанного.
 *
 * Без [roomId]/[eventId] пуш — только счётчик: сервер сообщает, что число непрочитанного изменилось
 * (так приходит «прочитано на другом устройстве»). Это не сообщение, и показывать нечего.
 */
data class PushPayload(
    val roomId: String?,
    val eventId: String?,
    /** Случайный ID сессии из `default_payload` (`iskra_account`). Нет — старый pusher без него. */
    val accountId: String?,
    /** Сколько у аккаунта непрочитанного на сервере. Null — сервер не сказал. */
    val unread: Int?,
) {
    val isMessage: Boolean get() = roomId != null && eventId != null

    companion object {
        /**
         * `data` сообщения FCM от gcm-pushkin Sygnal. При `event_id_only` в нём `room_id`, `event_id`,
         * `unread` и ключи `default_payload` — плоско, рядом. FCM v1 пускает в `data` только строки,
         * поэтому `unread` — тоже строка.
         */
        fun fromFcm(data: Map<String, String>): PushPayload = PushPayload(
            roomId = data[ROOM_ID]?.takeIf { it.isNotEmpty() },
            eventId = data[EVENT_ID]?.takeIf { it.isNotEmpty() },
            accountId = data[PushRegistration.ACCOUNT_KEY]?.takeIf { it.isNotEmpty() },
            unread = data[UNREAD]?.toIntOrNull(),
        )

        /**
         * Тело запроса Matrix push gateway (`/_matrix/push/v1/notify`), которое дистрибьютор UnifiedPush
         * передаёт как есть. `default_payload` здесь не плоский: сервер кладёт `data` pusher'а (без
         * `url`) в `devices[].data`. Не JSON или не уведомление — null: от дистрибьютора может прийти
         * что угодно, и чужое молча отбрасывается.
         */
        fun fromMatrixNotify(body: String): PushPayload? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val notification = root["notification"] as? JsonObject ?: return null
            val device = runCatching { notification["devices"]?.jsonArray?.firstOrNull()?.jsonObject }.getOrNull()
            val defaults = (device?.get("data") as? JsonObject)?.get("default_payload") as? JsonObject
            return PushPayload(
                roomId = notification.string(ROOM_ID),
                eventId = notification.string(EVENT_ID),
                accountId = defaults?.string(PushRegistration.ACCOUNT_KEY),
                unread = ((notification["counts"] as? JsonObject)?.get(UNREAD) as? JsonPrimitive)?.intOrNull,
            )
        }

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

        private const val ROOM_ID = "room_id"
        private const val EVENT_ID = "event_id"
        private const val UNREAD = "unread"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
