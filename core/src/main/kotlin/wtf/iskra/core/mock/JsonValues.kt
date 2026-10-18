package wtf.iskra.core.mock

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * JSON демо-сервера — обычные `Map` и `List`, как `[String: Any]` в Swift-оригинале. Сервер
 * перекладывает события из комнаты в комнату, правит их на месте и отдаёт кусками; дерево
 * неизменяемых `JsonElement` сделало бы каждую такую строчку втрое длиннее, а порт — непохожим на
 * `MockHomeserver.swift`, с которым его сверяют.
 */
internal typealias JSON = MutableMap<String, Any?>

internal fun json(vararg pairs: Pair<String, Any?>): JSON = linkedMapOf(*pairs)

internal object JsonValues {
    private val format = Json { encodeDefaults = true }

    fun encode(value: Any?): String = format.encodeToString(JsonElement.serializer(), toElement(value))

    /** Тело запроса как объект; что угодно другое (пустое, массив, мусор) — пустой объект. */
    fun decodeObject(text: String): JSON {
        if (text.isBlank()) return linkedMapOf()
        val element = runCatching { format.parseToJsonElement(text) }.getOrNull() ?: return linkedMapOf()
        @Suppress("UNCHECKED_CAST")
        return (fromElement(element) as? JSON) ?: linkedMapOf()
    }

    fun toElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toElement(item) })
        is Iterable<*> -> JsonArray(value.map(::toElement))
        is Array<*> -> JsonArray(value.map(::toElement))
        else -> JsonPrimitive(value.toString())
    }

    fun fromElement(element: JsonElement): Any? = when (element) {
        JsonNull -> null
        is JsonObject -> element.entries.associateTo(linkedMapOf<String, Any?>()) { (key, item) -> key to fromElement(item) }
        is JsonArray -> element.mapTo(mutableListOf()) { fromElement(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
    }
}

// Чтение полей так же коротко, как `as? JSON` / `as? Int` в Swift.

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): JSON? = this as? JSON

internal fun Any?.str(): String? = this as? String

internal fun Any?.int(): Int? = (this as? Number)?.toInt()

internal fun Any?.long(): Long? = (this as? Number)?.toLong()

@Suppress("UNCHECKED_CAST")
internal fun Any?.list(): List<Any?>? = this as? List<Any?>

/** Мелкая копия события: в Swift словарь копируется сам, здесь — руками, где правка на месте. */
internal fun JSON.copy(): JSON = LinkedHashMap(this)
