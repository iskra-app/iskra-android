package wtf.iskra.core.support

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration

/** Операция не ответила за отведённое время. */
class TimeoutError(val limit: Duration) : Exception("Нет ответа за $limit")

/**
 * Выполняет [operation] и сдаётся через [limit]. Порт `withTimeout` из `IskraCore/Support/Timeout.swift`.
 *
 * Каждый вызов SDK, которого ждёт человек, ограничен по времени (docs/PROMPT.md, 4.3): спиннер без
 * конца — это баг. Главный пример — `startSignIn`: сервер, который принял соединение и молчит,
 * оставлял бы «Продолжить» крутиться вечно.
 *
 * **Почему не `kotlinx.coroutines.withTimeout`.** Он отменяет дочернюю корутину и *ждёт её
 * завершения*. Блокирующий вызов, застрявший в Rust, отмену не услышит и продержит вызывающего
 * сколько захочет — ровно то зависание, которое здесь ограничивается. Поэтому работа запускается
 * отдельно от вызывающего (не дочерней, на [Dispatchers.IO]), отменяется при проигрыше, но
 * **не дожидается**. Проигравший вызов доработает сам и уйдёт в никуда.
 *
 * И вторая причина: тот `withTimeout` бросает `TimeoutCancellationException`, то есть *отмену*,
 * которую корутины по пути молча проглатывают как штатную. Здесь таймаут — обычная ошибка,
 * [TimeoutError], которую видно и которую можно назвать человеку словами.
 *
 * Имя другое, чтобы два `withTimeout` в одном файле не путались по импорту.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun <T> withTimeLimit(limit: Duration, operation: suspend () -> T): T {
    // Вызывающий, которого уже отменили, не запускает работу: это был бы сетевой вызов от имени
    // того, кто уже ушёл. На iOS это место однажды повесило вызывающего навсегда (TimeoutTests).
    currentCoroutineContext().ensureActive()
    val work = CoroutineScope(Dispatchers.IO + SupervisorJob()).async(start = CoroutineStart.DEFAULT) {
        operation()
    }
    try {
        return select {
            work.onAwait { it }
            onTimeout(limit) { throw TimeoutError(limit) }
        }
    } catch (error: Throwable) {
        // Проиграли — по таймауту, по отмене вызывающего или по ошибке самой работы. Работа
        // отменяется, но её никто не ждёт.
        work.cancel(error as? CancellationException ?: CancellationException("withTimeLimit", error))
        throw error
    }
}
