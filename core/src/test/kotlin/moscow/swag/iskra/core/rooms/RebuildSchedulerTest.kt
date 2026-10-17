package moscow.swag.iskra.core.rooms

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * Склеивание диффов в пересборки (docs/PROMPT.md, 4.1). Главное — пачка склеивается, а поток диффов
 * не может пересборку задушить: она всегда доходит до конца.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuildSchedulerTest {
    @Test
    fun aBurstIsOneRebuildAfterTheDebounce() = runTest {
        val asked = mutableListOf<Set<String>?>()
        val scheduler = RebuildScheduler(this, 50.milliseconds) { asked += it }

        scheduler.schedule(setOf("a"))
        scheduler.schedule(setOf("b"))
        advanceTimeBy(49.milliseconds)
        runCurrent()
        assertEquals(emptyList(), asked)

        advanceUntilIdle()
        // Первая пересборка — полная: стор ещё ничего не собирал.
        assertEquals(listOf<Set<String>?>(null), asked)

        scheduler.schedule(setOf("a"))
        scheduler.schedule(setOf("b"))
        advanceUntilIdle()
        assertEquals(listOf(null, setOf("a", "b")), asked)
    }

    /** На iOS «отменить и начать заново» морило список голодом при непрерывных диффах. */
    @Test
    fun diffsDuringARebuildRaiseAFlagNotAKnife() = runTest {
        val asked = mutableListOf<Set<String>?>()
        lateinit var scheduler: RebuildScheduler
        scheduler = RebuildScheduler(this, 50.milliseconds) { changed ->
            asked += changed
            // Пока идёт пересборка, приходит ещё дифф — и ещё один.
            if (asked.size == 1) {
                scheduler.schedule(setOf("x"))
                delay(100)
                scheduler.schedule(setOf("y"))
            }
        }
        scheduler.schedule()
        advanceUntilIdle()
        // Первая дошла до конца, вторая — одна на оба диффа.
        assertEquals(listOf(null, setOf("x", "y")), asked)
    }

    @Test
    fun aRoomlessRequestAsksForEveryRoom() = runTest {
        val asked = mutableListOf<Set<String>?>()
        val scheduler = RebuildScheduler(this, 50.milliseconds) { asked += it }
        scheduler.schedule()
        advanceUntilIdle()
        scheduler.schedule(setOf("a"))
        scheduler.schedule()
        advanceUntilIdle()
        assertEquals(listOf<Set<String>?>(null, null), asked)
    }

    /** Отменённый прогон не публикует и не сносит регистрацию следующего. */
    @Test
    fun cancelForgetsWhatWasOwed() = runTest {
        val asked = mutableListOf<Set<String>?>()
        val scheduler = RebuildScheduler(this, 50.milliseconds) { asked += it }
        scheduler.schedule(setOf("a"))
        scheduler.cancel()
        advanceUntilIdle()
        assertEquals(emptyList(), asked)

        scheduler.schedule(setOf("b"))
        advanceUntilIdle()
        assertEquals(listOf<Set<String>?>(null), asked)
    }

    @Test
    fun aRebuildThatThrowsDoesNotJamTheScheduler() = runTest {
        val asked = mutableListOf<Set<String>?>()
        var first = true
        // Как у стора: супервизор, и упавшая пересборка не роняет соседей.
        val scope = CoroutineScope(coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val scheduler = RebuildScheduler(scope, 50.milliseconds) {
            asked += it
            if (first) {
                first = false
                error("boom")
            }
        }
        scheduler.schedule()
        advanceUntilIdle()
        scheduler.schedule(setOf("a"))
        advanceUntilIdle()
        assertEquals(2, asked.size)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
