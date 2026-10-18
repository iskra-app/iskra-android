package wtf.iskra.core.rooms

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Когда заголовку ждать ответа сервера. Порт `FirstAnswerWatchTests` (без состояний SDK: им нужен натив). */
class FirstAnswerWatchTest {
    @Test
    fun waitsOnlyWhileSyncIsUpAndUnanswered() {
        assertTrue(FirstAnswerWatch.isWaiting(stopped = false, paused = false, offline = false, answered = false))
        assertFalse(FirstAnswerWatch.isWaiting(stopped = true, paused = false, offline = false, answered = false))
        assertFalse(FirstAnswerWatch.isWaiting(stopped = false, paused = true, offline = false, answered = false))
        // Офлайн уже сказан на экране, и сильнее.
        assertFalse(FirstAnswerWatch.isWaiting(stopped = false, paused = false, offline = true, answered = false))
        assertFalse(FirstAnswerWatch.isWaiting(stopped = false, paused = false, offline = false, answered = true))
    }

    @Test
    fun updatingComesBeforeStalled() {
        assertTrue(FirstAnswerWatch.updatingAfter < FirstAnswerWatch.stalledAfter)
    }
}
