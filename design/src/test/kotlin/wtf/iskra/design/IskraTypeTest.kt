package wtf.iskra.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Межбуквенное — по динамическим метрикам Inter, а не разрядка Material (см. `InterTypography`). */
class IskraTypeTest {
    @Test
    fun trackingFollowsIntersCurve() {
        assertEquals(-0.011f, interTracking(16f), 0.0005f)
        assertEquals(-0.018f, interTracking(22f), 0.0005f)
        assertEquals(0.0005f, interTracking(12f), 0.0005f)
    }

    @Test
    fun largerTypeIsSetTighter() {
        val sizes = listOf(11f, 12f, 14f, 16f, 22f, 28f, 36f, 57f)
        assertTrue(sizes.zipWithNext().all { (small, large) -> interTracking(large) < interTracking(small) })
    }

    @Test
    fun noStyleKeepsMaterialsWideSpacing() {
        val body = InterTypography.bodyLarge.letterSpacing
        assertTrue(body.isEm && body.value < 0f)
        assertEquals(0f, GoogleSansTypography.bodyLarge.letterSpacing.value)
    }

    @Test
    fun googleSansIsTheDefault() {
        assertEquals(AppFont.GOOGLE_SANS, AppFont.fromStorage(null))
        assertEquals(AppFont.INTER, AppFont.fromStorage("inter"))
        assertEquals(GoogleSansTypography, iskraTypography(AppFont.Default))
    }
}
