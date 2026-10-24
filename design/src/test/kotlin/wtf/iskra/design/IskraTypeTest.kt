package wtf.iskra.design

import org.junit.Assert.assertEquals
import org.junit.Test

/** Шрифт один — Google Sans, и разрядка Material (рассчитанная на Roboto) к нему не применяется. */
class IskraTypeTest {
    @Test
    fun noStyleKeepsMaterialsWideSpacing() {
        val typography = iskraTypography()
        assertEquals(0f, typography.bodyLarge.letterSpacing.value)
        assertEquals(0f, typography.labelSmall.letterSpacing.value)
        assertEquals(0f, typography.displayLarge.letterSpacing.value)
    }

    @Test
    fun everyStyleIsSetInGoogleSans() {
        val typography = iskraTypography()
        assertEquals(GoogleSans, typography.bodyLarge.fontFamily)
        assertEquals(GoogleSans, typography.titleLarge.fontFamily)
        assertEquals(GoogleSans, typography.labelSmall.fontFamily)
    }
}
