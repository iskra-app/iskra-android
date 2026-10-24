package wtf.iskra.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaAutoDownloadTest {
    @Test
    fun alwaysAndNeverIgnoreTheNetwork() {
        assertTrue(MediaAutoDownload.ALWAYS.allows(expensive = true))
        assertTrue(MediaAutoDownload.ALWAYS.allows(expensive = false))
        assertFalse(MediaAutoDownload.NEVER.allows(expensive = false))
    }

    @Test
    fun wifiOnlyStopsOnAMeteredNetwork() {
        assertTrue(MediaAutoDownload.WIFI.allows(expensive = false))
        assertFalse(MediaAutoDownload.WIFI.allows(expensive = true))
    }

    @Test
    fun unknownStoredValueFallsBackToAlways() {
        assertEquals(MediaAutoDownload.ALWAYS, MediaAutoDownload.of("garbage"))
        assertEquals(MediaAutoDownload.WIFI, MediaAutoDownload.of("wifi"))
    }
}
