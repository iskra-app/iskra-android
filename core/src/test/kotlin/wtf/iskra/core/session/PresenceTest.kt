package wtf.iskra.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Присутствие: что сказал сервер и что из этого можно сказать человеку. Порт `PresenceTests` и `PresenceLabelTests`. */
class PresenceTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val zone: ZoneId = ZoneOffset.UTC

    private fun seen(secondsAgo: Long, state: Presence.State = Presence.State.OFFLINE) =
        Presence(state, now.minusSeconds(secondsAgo), isCurrentlyActive = false)

    @Test
    fun lastActiveAgoBecomesAMomentWhenTheAnswerArrives() {
        val presence = Presence.parse("""{"presence":"unavailable","last_active_ago":90000,"currently_active":false}""", now)!!
        assertEquals(Presence.State.UNAVAILABLE, presence.state)
        assertEquals(now.minusSeconds(90), presence.lastActive)
    }

    @Test
    fun bareOfflineIsNotWorthShowing() {
        assertFalse(Presence.parse("""{"presence":"offline"}""", now)!!.isWorthShowing)
        assertTrue(Presence.parse("""{"presence":"online"}""", now)!!.isWorthShowing)
        assertTrue(Presence.parse("""{"presence":"offline","last_active_ago":5000}""", now)!!.isWorthShowing)
    }

    @Test
    fun onlineAndCurrentlyActiveOutrankTheTimestamp() {
        assertEquals(PresenceLabel.Online, PresenceLabel.of(seen(86_400, Presence.State.ONLINE), now, zone))
        assertEquals(PresenceLabel.Online, PresenceLabel.of(Presence(Presence.State.OFFLINE, null, true), now, zone))
    }

    @Test
    fun nothingToSayIsNull() {
        assertNull(PresenceLabel.of(Presence(Presence.State.OFFLINE, null, false), now, zone))
    }

    @Test
    fun agesRoundDownAndNeverGoNegative() {
        assertEquals(PresenceLabel.JustNow, PresenceLabel.of(seen(30), now, zone))
        assertEquals(PresenceLabel.JustNow, PresenceLabel.of(seen(-5), now, zone))
        assertEquals(PresenceLabel.MinutesAgo(5), PresenceLabel.of(seen(5 * 60 + 59), now, zone))
        assertEquals(PresenceLabel.HoursAgo(3), PresenceLabel.of(seen(3 * 3600 + 10), now, zone))
    }

    @Test
    fun pastHalfADayItIsClockTimeYesterdayOrDate() {
        // 12:00 сейчас; 20 часов назад — вчера в 16:00.
        assertEquals(PresenceLabel.Yesterday, PresenceLabel.of(seen(20 * 3600), now, zone))
        // 13 часов назад — сегодня в 23:00? Нет: вчера. Сегодня в 00:30 — 11.5 часа, это ещё часы.
        assertEquals(PresenceLabel.HoursAgo(11), PresenceLabel.of(seen(11 * 3600 + 1800), now, zone))
        val older = seen(5 * 24 * 3600)
        assertEquals(PresenceLabel.OnDate(older.lastActive!!), PresenceLabel.of(older, now, zone))
        // Позднее утро того же дня: 12 ч назад от 23:00 — сегодня.
        val lateNow = Instant.parse("2026-10-24T23:00:00Z")
        val morning = Presence(Presence.State.OFFLINE, Instant.parse("2026-10-24T10:20:00Z"), false)
        assertEquals(PresenceLabel.AtTime(morning.lastActive!!), PresenceLabel.of(morning, lateNow, zone))
    }
}
