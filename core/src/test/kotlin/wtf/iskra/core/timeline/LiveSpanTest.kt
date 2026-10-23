package wtf.iskra.core.timeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** `Location.LiveSpan` — когда трансляция ещё движется и есть ли у неё точка. Порт `Location.LiveSpan`. */
class LiveSpanTest {
    private val until = Instant.parse("2026-10-23T18:30:00Z")

    private fun span(isLive: Boolean = true, updatedAt: Instant? = until.minusSeconds(60)) =
        MessageKind.Location.LiveSpan(isLive, until, updatedAt)

    @Test
    fun `включена и не вышла за срок — движется`() {
        assertTrue(span().isActive(until.minusSeconds(1)))
    }

    @Test
    fun `в срок вышла — закончилась сама`() {
        assertFalse(span().isActive(until))
        assertFalse(span().isActive(until.plusSeconds(1)))
    }

    @Test
    fun `остановлена раньше срока — не движется`() {
        assertFalse(span(isLive = false).isActive(until.minusSeconds(600)))
    }

    @Test
    fun `без первой точки позиции нет`() {
        assertFalse(span(updatedAt = null).hasPosition)
        assertTrue(span().hasPosition)
    }
}
