package wtf.iskra.core.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * Что домашний сервер готов сказать о том, где человек. Порт `Presence`.
 *
 * Три собственных состояния Matrix и единственное число, что важно: [lastActive]. «Был(а) 5 мин назад» берётся из этого времени и
 * ни из чего другого — в частности никогда из последнего сообщения в разговоре: это другой факт в одежде присутствия. Человек, что
 * всё утро читал и ничего не писал, — не «был(а) в 09:14».
 *
 * Каждое поле необязательно в протоколе и здесь, потому что сервер с выключенным присутствием отвечает `offline` без времени вовсе,
 * и правильно рисовать для этого *ничего*, а не слово «не в сети».
 */
data class Presence(
    val state: State,
    /** Когда человек последний раз что-то делал, по мнению сервера, в миллисекундах Unix. Null — сервер не сказал. */
    val lastActive: Instant?,
    /** Слово самого сервера, что человек активен *прямо сейчас*, — весит больше [state], который клиент ставит руками и уходит. */
    val isCurrentlyActive: Boolean,
) {
    enum class State { ONLINE, UNAVAILABLE, OFFLINE }

    /**
     * Есть ли тут что показать. Голый `offline` без времени — то, что сервер с выключенным присутствием отвечает про всех, в том числе
     * про тех, кто явно пишет вам. Рисовать из него «не в сети» значило бы утверждать то, чего приложению не говорили.
     */
    val isWorthShowing: Boolean get() = isCurrentlyActive || state == State.ONLINE || lastActive != null

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * Прямо из `GET /_matrix/client/v3/presence/{userId}/status`. `last_active_ago` — длительность в миллисекундах, а не момент, и
         * считается от ответа *сервера*, поэтому превращается в момент здесь, когда пришёл ответ: возраст, что молча перестал стареть,
         * хранить нельзя.
         */
        fun parse(body: String, now: Instant = Instant.now()): Presence? {
            val json = runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val state = when (json["presence"]?.jsonPrimitive?.contentOrNull) {
                "online" -> State.ONLINE
                "unavailable" -> State.UNAVAILABLE
                else -> State.OFFLINE
            }
            val ago = json["last_active_ago"]?.jsonPrimitive?.doubleOrNull
            return Presence(
                state = state,
                lastActive = ago?.let { now.minusMillis(it.toLong()) },
                isCurrentlyActive = json["currently_active"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}

/** Сервер не сказал: присутствие там выключено или у нас с этим человеком нет общих комнат. Не ошибка для людей — «промолчать». */
class PresenceDeclined(val status: Int) : Exception()

/**
 * «в сети» · «был(а) 5 мин назад» · «был(а) в 10:20» · «был(а) 12.07.25». Единственное место, где приложение превращает метку времени
 * в утверждение о том, где человек, — отдельно от шапки, что его показывает, чтобы проверять тестом, а не глазами. Порт `PresenceLabel`.
 *
 * «Мин», а не «минут»: сокращение не склоняется, и «5 минут / 2 минуты / 1 минута» — ровно то, где ошибаются в одном падеже и выпускают.
 */
sealed interface PresenceLabel {
    data object Online : PresenceLabel
    data object JustNow : PresenceLabel
    data class MinutesAgo(val minutes: Int) : PresenceLabel
    data class HoursAgo(val hours: Int) : PresenceLabel

    /** Сегодня, но давно: «был(а) в 10:20». */
    data class AtTime(val at: Instant) : PresenceLabel
    data object Yesterday : PresenceLabel
    /** Раньше: числом, чтобы читалось, а не разбиралось. */
    data class OnDate(val at: Instant) : PresenceLabel

    companion object {
        /** Null — не говорить ничего: самое частое, присутствие выключено на многих серверах (matrix.org среди них). */
        fun of(presence: Presence, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): PresenceLabel? {
            // Слово сервера о «прямо сейчас» весит больше всего ниже.
            if (presence.isCurrentlyActive || presence.state == Presence.State.ONLINE) return Online
            val lastActive = presence.lastActive ?: return null
            // Не меньше нуля: часы сервера и устройства расходятся на секунды чаще, чем кто-то из них неправ, а отрицательный возраст
            // рисуется как «был(а) -1 мин назад».
            val ago = maxOf(0L, now.epochSecond - lastActive.epochSecond)
            return when {
                ago < MINUTE -> JustNow
                ago < HOUR -> MinutesAgo((ago / MINUTE).toInt())
                // Двенадцать, не двадцать четыре: «был(а) 19 ч назад» — арифметика, а не ответ.
                ago < 12 * HOUR -> HoursAgo((ago / HOUR).toInt())
                else -> {
                    val day = lastActive.atZone(zone).toLocalDate()
                    val today = now.atZone(zone).toLocalDate()
                    when (day) {
                        today -> AtTime(lastActive)
                        today.minusDays(1) -> Yesterday
                        else -> OnDate(lastActive)
                    }
                }
            }
        }

        private const val MINUTE = 60L
        private const val HOUR = 3600L
    }
}
