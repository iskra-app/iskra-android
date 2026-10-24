package wtf.iskra.core.timeline

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Откуда пересланное: то, что событие само о себе сказало. Все поля необязательны — читатель без `sender` рисует просто «Переслано». */
data class ForwardOrigin(
    val senderId: String? = null,
    val senderName: String? = null,
    val eventId: String? = null,
    val roomId: String? = null,
    val timestamp: Instant? = null,
) {
    /** Имя, а нет его — Matrix ID. */
    val displayName: String? get() = senderName?.takeIf { it.isNotEmpty() } ?: senderId
}

/**
 * `m.forwarded` — пометка пересланного (MSC4153/MSC4553). Порт `Forwarded`.
 *
 * Читается из JSON самого события — единственного места, где SDK хранит поле, для которого у него нет имени, — и только для видов,
 * что вообще пересылаются; один раз на событие (кэш).
 */
internal object Forwarded {
    const val KEY = "m.forwarded"
    private const val CACHE_LIMIT = 4096
    private val cache = ConcurrentHashMap<String, java.util.Optional<ForwardOrigin>>()

    /** Пометка события, если оно пересылка. [json] зовётся только когда события ещё не знали. */
    fun origin(eventId: String, json: () -> String?): ForwardOrigin? {
        cache[eventId]?.let { return it.orElse(null) }
        val found = json()?.let(::origin)
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[eventId] = java.util.Optional.ofNullable(found)
        return found
    }

    fun origin(eventJson: String): ForwardOrigin? {
        val event = runCatching { Json.parseToJsonElement(eventJson).jsonObject }.getOrNull() ?: return null
        val content = event["content"] as? JsonObject ?: return null
        val forwarded = content[KEY] as? JsonObject ?: return null
        fun text(name: String) = (forwarded[name] as? JsonPrimitive)?.contentOrNull
        return ForwardOrigin(
            senderId = text("sender"),
            senderName = text("displayname"),
            eventId = text("event_id"),
            roomId = text("room_id"),
            timestamp = (forwarded["origin_server_ts"] as? JsonPrimitive)?.longOrNull?.let(Instant::ofEpochMilli),
        )
    }

    /**
     * Что отправить, чтобы слова ушли помеченными. Только *что* это пересылка и когда сказано впервые — никогда кто и где: помеченная
     * пересылка идёт только в комнату без шифрования, а слова могут быть из той, что зашифрована, и имя автора или ID события из
     * закрытого разговора, написанные там, где их читает сервер, сказали бы, кто говорил и что разговор есть. Null — не слова.
     */
    fun content(message: Message): String? {
        val (msgtype, body) = when (val kind = message.kind) {
            is MessageKind.Text -> "m.text" to kind.body
            is MessageKind.Notice -> "m.notice" to kind.body
            is MessageKind.Emote -> "m.emote" to kind.body
            else -> return null
        }
        val original = buildJsonObject {
            put("msgtype", msgtype)
            put("body", body)
            message.formattedBody?.let {
                put("format", "org.matrix.custom.html")
                put("formatted_body", it)
            }
        }
        val at = message.forwardedFrom?.timestamp ?: if (message.forwardedFrom == null) message.timestamp else null
        val forwarded = buildJsonObject {
            at?.let { put("origin_server_ts", it.toEpochMilli()) }
            put("content", original)
        }
        val content = buildJsonObject {
            original.forEach { (name, value) -> put(name, value) }
            put(KEY, forwarded)
            // Пересылкой никого не упоминают: пустое множество — то, что мешает старым правилам принять имя в тексте за упоминание.
            put("m.mentions", buildJsonObject {})
        }
        return content.toString()
    }
}
