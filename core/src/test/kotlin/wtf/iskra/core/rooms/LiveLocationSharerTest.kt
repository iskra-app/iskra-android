package wtf.iskra.core.rooms

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Когда трансляция шлёт точку — порт `LiveLocationTests`, и запись на диске. */
class LiveLocationSharerTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")
    private val here = LivePoint(55.7558, 37.6173, now)

    @Test
    fun theFirstPointAlwaysGoes() {
        assertTrue(LiveLocationSharer.shouldSend(here, null, null, now))
    }

    @Test
    fun standingStillSendsNothingUntilTheSilenceIsLongEnough() {
        assertFalse(LiveLocationSharer.shouldSend(here, here, now.minusSeconds(20), now))
        assertTrue(LiveLocationSharer.shouldSend(here, here, now.minusSeconds(61), now))
    }

    @Test
    fun movingFarEnoughSends() {
        val north = LivePoint(55.7562, 37.6173, now) // ~45 м к северу
        assertTrue(LiveLocationSharer.shouldSend(north, here, now, now))
        val near = LivePoint(55.75585, 37.6173, now) // ~5 м
        assertFalse(LiveLocationSharer.shouldSend(near, here, now, now))
    }

    @Test
    fun distanceIsInMetres() {
        val degree = LiveLocationSharer.distance(LivePoint(0.0, 0.0), LivePoint(1.0, 0.0))
        assertEquals(111_195.0, degree, 100.0)
    }

    @Test
    fun theGeoUriUsesAPointWhateverTheLocale() {
        assertEquals("geo:55.755800,37.617300", here.geoUri)
    }

    @Test
    fun theLedgerRoundTripsAndSurvivesGarbage() {
        val shares = mapOf("!a:x.org" to now, "!b:x.org" to now.plusSeconds(60))
        assertEquals(shares, LiveShareLedger.decode(LiveShareLedger.encode(shares)))
        assertEquals(emptyMap(), LiveShareLedger.decode("не то\n\t5\n!a:x\tabc"))
        assertEquals(emptyMap(), LiveShareLedger.decode(null))
    }
}
