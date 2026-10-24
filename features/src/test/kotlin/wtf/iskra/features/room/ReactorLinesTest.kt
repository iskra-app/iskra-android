package wtf.iskra.features.room

import wtf.iskra.core.timeline.Reactor
import wtf.iskra.core.timeline.Reader
import kotlin.test.Test
import kotlin.test.assertEquals

/** Что показывают вкладки листа «Кто отреагировал»: все — по разу, прочитавшие без реакции — отдельно. */
class ReactorLinesTest {
    private val reactors = listOf(Reactor("👍", "@a:x", "A", null), Reactor("❤️", "@b:x", "B", null))
    private val readers = listOf(Reader("@a:x", "A", null, null), Reader("@c:x", "C", null, null))

    @Test
    fun everybodyIsListedOnce() {
        assertEquals(listOf("@a:x", "@b:x", "@c:x"), lines(reactors, readers, null).map { it.userId })
    }

    @Test
    fun theReadTabHoldsEveryReader() {
        assertEquals(listOf("@a:x", "@c:x"), lines(reactors, readers, "\u0000read").map { it.userId })
    }

    @Test
    fun anEmojiTabHoldsItsPeople() {
        assertEquals(listOf("@b:x"), lines(reactors, readers, "❤️").map { it.userId })
    }
}
