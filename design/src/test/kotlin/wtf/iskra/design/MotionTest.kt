package wtf.iskra.design

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals

class MotionTest {
    /** Пружина Telegram в меню: отклик 0,265 с, затухание 0,775 — как на iOS. */
    @Test
    fun swiftUiSpringBecomesStiffness() {
        val spring = Theme.Timing.menuOpens
        val omega = 2 * PI / 0.265
        assertEquals((omega * omega).toFloat(), spring.stiffness, 0.01f)
        assertEquals(0.775f, spring.dampingFraction)
    }

    /** `bounce` SwiftUI — это `1 − затухание`. */
    @Test
    fun bounceIsOneMinusDamping() {
        assertEquals(0.82f, Theme.Timing.arrival.dampingFraction, 1e-6f)
        assertEquals(1f, Theme.Timing.stepChanges.dampingFraction)
    }

    @Test
    fun curvesKeepTheirDuration() {
        assertEquals(300, Theme.Timing.messageMakesRoom.durationMillis)
        assertEquals(200, Theme.Timing.menuCloses.durationMillis)
    }
}
