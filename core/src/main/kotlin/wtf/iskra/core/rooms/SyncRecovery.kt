package wtf.iskra.core.rooms

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Когда снова поднимать синхронизацию, которая не доходит до сервера: 10, 20, 40 секунд, дальше раз в
 * минуту (docs/PROMPT.md, 4.6). Порт арифметики `RoomListStore.scheduleRecovery`.
 *
 * «Ожидание сети» после долгого фона без этого могло длиться вечно: собственные повторы SDK идут по тем же
 * соединениям, а они после часов сна мертвы так, что быстро этого никто не замечает. Единственное, что их
 * чистит, — то, что человек делает руками: убить приложение и открыть снова. Это — вежливая версия.
 */
internal object SyncRecovery {
    /** Пауза перед попыткой номер [attempts] (с нуля). Возврат сети устройства её не ждёт — см. `RoomListStore`. */
    fun delay(attempts: Int): Duration = minOf(CEILING_SECONDS, FIRST_SECONDS shl minOf(attempts.coerceAtLeast(0), DOUBLINGS)).seconds

    const val FIRST_SECONDS = 10
    const val CEILING_SECONDS = 60
    private const val DOUBLINGS = 3
}

/**
 * Когда снова включать очередь отправки комнаты, остановившуюся сама, — та половина `PendingMessageManager`
 * Telegram, которую SDK оставляет нам. Порт `SendQueueRecovery.swift`.
 *
 * Очередь SDK сохраняет сообщение в зашифрованный стор до любой попытки, переживает убийство приложения и шлёт
 * по порядку. Чего она *не* делает — не пробует снова: запрос, упавший по причине, которая может пройти (нет
 * сети, сервер не ответил, 502 от прокси), SDK называет восстановимым и отвечает *выключением очереди комнаты*,
 * дожидаясь, пока ей скажут продолжать. Говорил только возврат синхронизации, а синхронизация не пропадает из-за
 * сервера, который просто отказывает слать. Сообщение, написанное в плохую связь, лежало, пока сеть не падала
 * *совсем* и не возвращалась, — на хорошем Wi-Fi это никогда.
 *
 * Каждый раз, когда очередь останавливается сама, её включают снова после паузы, что удваивается с каждой неудачей
 * подряд; цепочка забывается, когда очередь затихла надолго. Офлайн ждать нечего: синхронизация сообщит о
 * возвращении сети и включит все очереди разом — это раньше любой паузы.
 *
 * Значение, отдельно от стора, чтобы арифметику закрепил тест без клиента.
 */
internal class SendQueueRecovery {
    private var failuresInARow = 0
    private var lastFailure: Instant? = null

    /** Очередь остановилась. Сколько ждать, прежде чем включить снова. */
    fun failed(now: Instant = Instant.now()): Duration {
        val last = lastFailure
        if (last != null && now.epochSecond - last.epochSecond > QUIET_SECONDS) failuresInARow = 0
        lastFailure = now
        failuresInARow += 1
        // 1, 2, 4, 8, 16, 30, 30… с. Первая короткая: обычная причина — один потерянный запрос на связи, которая
        // в остальном в порядке. Потолок низкий: человек смотрит на часы рядом со своим сообщением.
        return minOf(CEILING_SECONDS.toLong(), 1L shl minOf(failuresInARow - 1, MAX_SHIFT)).seconds
    }

    /** Очереди затихли надолго, или сеть вернулась — а это новое начало по определению. */
    fun reset() {
        failuresInARow = 0
        lastFailure = null
    }

    companion object {
        const val CEILING_SECONDS = 30

        /** Длиннее потолка: очередь, падающая на каждой попытке на потолке, никогда не выглядит затихшей. */
        const val QUIET_SECONDS = 60L
        private const val MAX_SHIFT = 5
    }
}
