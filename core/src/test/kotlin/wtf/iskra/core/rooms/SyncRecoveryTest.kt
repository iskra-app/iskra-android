package wtf.iskra.core.rooms

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Паузы перезапуска: синхронизации (10, 20, 40, 60…) и очереди отправки (1, 2, 4… 30). Порт `SendQueueRecoveryTests`. */
class SyncRecoveryTest {
    private val start = Instant.parse("2026-10-23T12:00:00Z")

    @Test
    fun syncRestartsAfterTenTwentyFortyThenAMinute() {
        assertEquals(listOf(10, 20, 40, 60, 60, 60).map { it.seconds }, (0..5).map(SyncRecovery::delay))
    }

    @Test
    fun negativeAttemptsAreTheFirst() {
        assertEquals(10.seconds, SyncRecovery.delay(-3))
    }

    @Test
    fun queuePauseDoublesUpToTheCeiling() {
        val recovery = SendQueueRecovery()
        val pauses = (0 until 8).map { recovery.failed(start.plusSeconds(it.toLong())) }
        assertEquals(listOf(1, 2, 4, 8, 16, 30, 30, 30).map { it.seconds }, pauses)
    }

    @Test
    fun aFailureAfterAQuietSpellStartsTheRunAgain() {
        val recovery = SendQueueRecovery()
        recovery.failed(start)
        recovery.failed(start.plusSeconds(1))
        assertEquals(4.seconds, recovery.failed(start.plusSeconds(2)))
        assertEquals(1.seconds, recovery.failed(start.plusSeconds(2 + SendQueueRecovery.QUIET_SECONDS + 1)))
    }

    @Test
    fun aQueueFailingAtTheCeilingNeverLooksQuiet() {
        assertTrue(SendQueueRecovery.CEILING_SECONDS < SendQueueRecovery.QUIET_SECONDS)
    }

    @Test
    fun theNetworkComingBackForgetsTheRun() {
        val recovery = SendQueueRecovery()
        recovery.failed(start)
        recovery.failed(start)
        recovery.reset()
        assertEquals(1.seconds, recovery.failed(start))
    }
}
