package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import wtf.iskra.core.R
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Где человек сейчас. Широта и долгота в градусах. */
data class LivePoint(val latitude: Double, val longitude: Double, val at: Instant = Instant.now()) {
    /** `geo:`-адрес точки. Точка, а не запятая: «55,75» на немецком — две пары чисел, а не одна. */
    val geoUri: String get() = String.format(Locale.ROOT, "geo:%.6f,%.6f", latitude, longitude)
}

/** Откуда берутся точки: устройство. Живёт в `:features` (там `LocationManager`), чтобы `:core` не знал о системе. */
interface LocationSource {
    /** Разрешено ли приложению знать, где человек. Не спрашивает — спрашивает экран. */
    fun isAllowed(): Boolean

    /** Точки, пока кто-то собирает поток; перестали собирать — перестали слушать датчик. */
    fun updates(): Flow<LivePoint>
}

/**
 * Делиться тем, где ты, пока идёшь, и сколько-то времени — живая геопозиция Telegram, MSC3489 в Matrix. Порт
 * `LiveLocationSharer`.
 *
 * На сервере трансляция — две вещи: `beacon_info` («этот человек делится, до такого-то», `startLiveLocationShare`) и поток
 * `beacon`-точек под ним (`sendLiveLocation`). SDK рисует пару как одно сообщение с движущейся картой; здесь — половина,
 * что его кормит.
 *
 * Один датчик на все комнаты: устройство в одном месте, и три чата, которые его просят, — всё равно один GPS. Точка уходит,
 * когда человек прошёл достаточно или прошло достаточно времени с прошлой ([shouldSend]), и не чаще: каждая точка — событие
 * в комнате, и дорога до магазина не должна быть четырьмя сотнями.
 *
 * Трансляция кончается сама, когда время вышло, и тогда приложение *говорит*, что она кончилась (`stopLiveLocationShare`),
 * а не оставляет маяк, который просто истечёт: карта у собеседника перестаёт двигаться в тот же миг, но только второй способ
 * объясняет почему.
 *
 * Идёт и в кармане — для этого foreground-сервис типа `location` (`LiveLocationService`): пока что-то транслируется, в шторке
 * видно, что положение уходит с телефона, и «Остановить» под рукой.
 */
class LiveLocationSharer internal constructor(
    private val roomFor: (String) -> Room?,
    private val source: LocationSource?,
    /** Куда записывать трансляции, пока они идут: убитое в кармане приложение иначе молча перестаёт слать точки. Null — не пишем (демо). */
    private val ledger: File?,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) {
    /** Что не вышло. Слова — `live_location_*`. */
    enum class Failure(val explanation: Int) {
        START(R.string.live_location_start_failed),
        DENIED(R.string.live_location_denied),

        /** Комнате не сказали, что трансляция кончилась: другие могут видеть её живой, пока не истечёт. */
        STOP(R.string.live_location_stop_failed),
    }

    data class State(
        /** Комнаты, куда идут точки, и до какого часа. */
        val shares: Map<String, Instant> = emptyMap(),
        /** Комнаты, куда точка уже ушла. Пока нет — у других карта без метки, а уведомление говорит «ищем вас». */
        val roomsWithPoints: Set<String> = emptySet(),
        val failure: Failure? = null,
    ) {
        fun isSharing(roomId: String, at: Instant): Boolean = shares[roomId]?.isAfter(at) == true
    }

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    private val lastSent = HashMap<String, Pair<LivePoint, Instant>>()
    private var latest: LivePoint? = null
    private var tracking: Job? = null
    private var expiry: Job? = null

    fun isSharing(roomId: String): Boolean = state.value.isSharing(roomId, now())

    fun dismissFailure() = mutable.update { it.copy(failure = null) }

    /** Разрешено ли знать, где человек — экран спрашивает перед [start]. */
    fun isAllowed(): Boolean = source?.isAllowed() == true

    /** Нет датчика — нет и пункта «Делиться». */
    val isAvailable: Boolean get() = source != null

    private fun fail(failure: Failure) = mutable.update { it.copy(failure = failure) }

    /** Поделиться с комнатой на [duration]. Отказ системы кончает всё здесь — и говорит об этом. */
    suspend fun start(roomId: String, duration: Duration) {
        val room = roomFor(roomId)
        if (source == null || room == null) return fail(Failure.START)
        if (!source.isAllowed()) return fail(Failure.DENIED)
        try {
            withTimeLimit(SDK_LIMIT) { room.startLiveLocationShare(duration.inWholeMilliseconds.toULong()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "трансляция не началась: ${Log.describe(error)}")
            return fail(Failure.START)
        }
        lastSent.remove(roomId)
        mutable.update {
            it.copy(
                shares = it.shares + (roomId to now().plusMillis(duration.inWholeMilliseconds)),
                roomsWithPoints = it.roomsWithPoints - roomId,
                failure = null,
            )
        }
        persist()
        track()
        // Что показать сразу, а не карта, что ждёт следующего шага.
        latest?.let { send(it, roomId, force = true) }
        scheduleExpiry()
    }

    /**
     * Остановить: человек нажал «Остановить» или время вышло.
     *
     * Две половины, и ломаются они по-разному. Точки перестают идти в ту же секунду: трансляции нет в списке до того, как
     * что-то ожидается, так что медленный сервер не значит ещё одну позицию, ушедшую с телефона. Сказать *комнате* — вот что
     * может не получиться, и раньше не получалось молча: здесь трансляции уже нет, человек видел, как она кончилась, а у
     * остальных карта продолжала считать себя живой до таймаута. Поэтому — повторы, а остановка, что так и не дошла,
     * говорится вслух ([Failure.STOP]).
     */
    suspend fun stop(roomId: String, retries: Int = 2) {
        if (state.value.shares[roomId] == null) return
        lastSent.remove(roomId)
        mutable.update { it.copy(shares = it.shares - roomId, roomsWithPoints = it.roomsWithPoints - roomId) }
        persist()
        if (state.value.shares.isEmpty()) tracking?.cancel().also { tracking = null }
        scheduleExpiry()

        var wait = 1.seconds
        for (attempt in 0..retries) {
            if (attempt > 0) {
                delay(wait)
                wait *= RETRY_GROWTH
            }
            val room = roomFor(roomId) ?: break
            try {
                withTimeLimit(SDK_LIMIT) { room.stopLiveLocationShare() }
                return
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "остановка трансляции не принята (попытка ${attempt + 1}): ${Log.describe(error)}")
            }
        }
        fail(Failure.STOP)
    }

    /** Все — для закрываемого сеанса: ничто не должно слать через клиент, который уходит. */
    suspend fun stopAll() {
        for (roomId in state.value.shares.keys.toList()) stop(roomId, retries = 0)
        expiry?.cancel()
        expiry = null
    }

    /**
     * Что прошлый запуск транслировал и не закончил — дальше: те, у кого осталось время, идут дальше, а те, что вышли, пока
     * приложения не было, объявляются комнате законченными — никто до этого не дошёл. Разрешение не спрашивает: трансляция
     * стартовала только с ним, а если его с тех пор забрали — всё кончается здесь.
     */
    suspend fun resume() {
        val file = ledger ?: return
        if (state.value.shares.isNotEmpty()) return
        val stored = LiveShareLedger.decode(runCatching { file.readText() }.getOrNull())
        if (stored.isEmpty()) return
        val live = stored.filterValues { it.isAfter(now()) }
        val allowed = source?.isAllowed() == true
        if (allowed && live.isNotEmpty()) {
            mutable.update { it.copy(shares = live) }
            track()
            scheduleExpiry()
        } else {
            runCatching { file.delete() }
        }
        for (roomId in stored.keys) {
            if (allowed && live.containsKey(roomId)) continue
            val room = roomFor(roomId) ?: continue
            runCatching { withTimeLimit(SDK_LIMIT) { room.stopLiveLocationShare() } }
        }
    }

    // MARK: - Точки

    private fun track() {
        val source = source ?: return
        if (tracking?.isActive == true) return
        tracking = scope.launch {
            try {
                source.updates().collect { point -> received(point) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "положение не читается: ${Log.describe(error)}")
            }
        }
    }

    private suspend fun received(point: LivePoint) {
        latest = point
        for (roomId in state.value.shares.keys.toList()) send(point, roomId, force = false)
    }

    private suspend fun send(point: LivePoint, roomId: String, force: Boolean) {
        if (!isSharing(roomId)) return
        val last = lastSent[roomId]
        if (!force) {
            if (last != null && java.time.Duration.between(last.second, now()).seconds < MINIMUM_INTERVAL.inWholeSeconds) return
            if (!shouldSend(point, last?.first, last?.second, now())) return
        }
        val room = roomFor(roomId) ?: return
        lastSent[roomId] = point to now()
        try {
            withTimeLimit(SDK_LIMIT) { room.sendLiveLocation(point.geoUri) }
            mutable.update { it.copy(roomsWithPoints = it.roomsWithPoints + roomId) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "точка трансляции не принята: ${Log.describe(error)}")
        }
    }

    /** Один таймер на ближайший конец, заводится заново, когда список меняется. */
    private fun scheduleExpiry() {
        expiry?.cancel()
        val next = state.value.shares.values.minOrNull() ?: run {
            expiry = null
            return
        }
        expiry = scope.launch {
            delay(max(0L, next.toEpochMilli() - now().toEpochMilli()))
            for ((roomId, until) in state.value.shares.toList()) {
                if (!until.isAfter(now())) stop(roomId)
            }
            scheduleExpiry()
        }
    }

    private fun persist() {
        val file = ledger ?: return
        val shares = state.value.shares
        runCatching {
            if (shares.isEmpty()) file.delete() else file.writeText(LiveShareLedger.encode(shares))
        }
    }

    companion object {
        /** Не ближе этого друг к другу, как бы быстро человек ни шёл. */
        val MINIMUM_INTERVAL = 15.seconds

        /** Прошёл столько — точка уходит. */
        const val MINIMUM_DISTANCE_METERS = 30.0

        /** Стоит на месте дольше этого — точка уходит всё равно: «жив и здесь». */
        val MAXIMUM_SILENCE = 60.seconds

        private val SDK_LIMIT = 30.seconds
        private const val RETRY_GROWTH = 3
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Достаточно далеко или достаточно давно от последней точки, ушедшей в ту комнату. Первая идёт всегда. */
        fun shouldSend(point: LivePoint, last: LivePoint?, lastAt: Instant?, now: Instant): Boolean {
            if (last == null || lastAt == null) return true
            return distance(point, last) >= MINIMUM_DISTANCE_METERS ||
                java.time.Duration.between(lastAt, now).seconds >= MAXIMUM_SILENCE.inWholeSeconds
        }

        /** Расстояние по большому кругу, метры (haversine). */
        fun distance(a: LivePoint, b: LivePoint): Double {
            val dLat = Math.toRadians(b.latitude - a.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
        }
    }
}

/** Что записано о трансляциях на диске: строка на комнату, «id комнаты, табуляция, миллисекунды конца». Идентификаторы комнат не содержат ни того, ни другого. */
internal object LiveShareLedger {
    fun encode(shares: Map<String, Instant>): String = shares.entries.joinToString("\n") { "${it.key}\t${it.value.toEpochMilli()}" }

    fun decode(text: String?): Map<String, Instant> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            val millis = parts.getOrNull(1)?.toLongOrNull()
            if (parts.size == 2 && parts[0].isNotEmpty() && millis != null && millis > 0) parts[0] to Instant.ofEpochMilli(millis) else null
        }.toMap()
    }
}
