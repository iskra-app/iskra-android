package wtf.iskra.core.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadProgressTest {
    @Test
    fun waitsForATotalBeforeDrawingAnything() {
        assertNull(TimelineMapper.fraction(0, 0))
        assertNull(TimelineMapper.fraction(5, -1))
    }

    @Test
    fun isTheShareThatHasGoneAndNeverMoreThanAll() {
        assertEquals(0.25, TimelineMapper.fraction(25, 100))
        assertEquals(1.0, TimelineMapper.fraction(120, 100))
    }
}
