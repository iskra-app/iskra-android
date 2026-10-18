package wtf.iskra.core.support

import kotlinx.coroutines.job
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Порт `TimeoutTests` из iOS: те же вопросы, те же причины. */
class TimeoutTest {
    @Test
    fun fastAnswerWins() = runBlocking {
        assertEquals("matrix.org", withTimeLimit(10.seconds) { "matrix.org" })
    }

    private class Boom : Exception()

    @Test
    fun operationErrorIsNotSwallowed() {
        runBlocking {
            assertFailsWith<Boom> { withTimeLimit(10.seconds) { throw Boom() } }
        }
    }

    @Test
    fun slowOperationTimesOut() {
        runBlocking {
            assertFailsWith<TimeoutError> { withTimeLimit(50.milliseconds) { delay(30.seconds) } }
        }
    }

    /**
     * Причина, по которой эта функция есть, а `withTimeout` из корутин не годится: операция, глухая
     * к отмене (как вызов FFI, застрявший в Rust), не должна держать вызывающего дольше дедлайна.
     */
    @Test
    fun uncancellableOperationStillTimesOut() {
        val started = TimeSource.Monotonic.markNow()
        runBlocking {
            assertFailsWith<TimeoutError> {
                withTimeLimit(50.milliseconds) { Thread.sleep(5_000) }
            }
        }
        assertTrue(started.elapsedNow() < 2.seconds, "withTimeLimit ждал операцию, от которой отказался")
    }

    /**
     * Вызывающий, которого отменили до того, как он дошёл до вызова, отпускается, а не висит. На iOS
     * это было зависание навсегда — в той самой ситуации, ради которой функция написана.
     */
    @Test
    fun alreadyCancelledCallerIsReleased() = runBlocking {
        val operationRan = AtomicBoolean(false)
        val job = launch {
            // Отменён до вызова, но работает до первой точки приостановки — как пуш, у которого
            // кончилось время, или экран, который закрыли.
            coroutineContext.job.cancel()
            withTimeLimit(30.seconds) {
                operationRan.set(true)
                Thread.sleep(30_000)
            }
        }
        withTimeout(2.seconds) { job.join() }
        assertTrue(job.isCancelled)
        assertFalse(operationRan.get(), "работа запустилась от имени того, кто уже ушёл")
    }

    /** Вызывающего отменили посреди ожидания — он освобождается сразу, а не по дедлайну. */
    @Test
    fun cancellingTheCallerReleasesIt() = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        val call = async { withTimeLimit(30.seconds) { Thread.sleep(30_000) } }
        delay(50.milliseconds)
        call.cancel()
        withTimeout(2.seconds) { call.join() }
        assertTrue(started.elapsedNow() < 2.seconds)
    }
}
