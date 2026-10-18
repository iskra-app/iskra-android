package wtf.iskra.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порт `PollTests.swift`. */
class PollTest {
    private val answers = listOf("a" to "Вторник", "b" to "Среда", "c" to "Четверг")

    private fun poll(votes: Map<String, List<String>>, max: Long = 1, disclosed: Boolean = true, ended: Boolean = false) =
        Poll.of("Когда?", disclosed, max, answers, votes, ended, "@me:x")

    @Test
    fun `доли — от людей, и опрос с несколькими ответами не больше целого`() {
        val p = poll(mapOf("a" to listOf("@1:x", "@2:x"), "b" to listOf("@1:x", "@me:x"), "c" to emptyList()), max = 3)
        assertEquals(3, p.voters)
        assertEquals(2.0 / 3.0, p.share(p.answers[0]), 1e-9)
        assertTrue(p.answers[1].isChosen)
        assertFalse(p.answers[0].isChosen)
    }

    @Test
    fun `голоса за ответ, которого нет, не считаются`() {
        assertEquals(1, poll(mapOf("z" to listOf("@1:x"), "a" to listOf("@2:x"))).voters)
    }

    @Test
    fun `результаты — после своего голоса в открытом и только в конце в закрытом`() {
        assertFalse(poll(mapOf("a" to listOf("@1:x"))).showsResults)
        assertTrue(poll(mapOf("a" to listOf("@me:x"))).showsResults)
        assertFalse(poll(mapOf("a" to listOf("@me:x")), disclosed = false).showsResults)
        assertTrue(poll(emptyMap(), disclosed = false, ended = true).showsResults)
    }

    @Test
    fun `победители — только в конце, с ничьей`() {
        assertFalse(poll(mapOf("a" to listOf("@1:x"))).answers[0].isWinner)
        val over = poll(mapOf("a" to listOf("@1:x"), "b" to listOf("@2:x")), ended = true)
        assertEquals(listOf(true, true, false), over.answers.map { it.isWinner })
        assertTrue(poll(emptyMap(), ended = true).answers.none { it.isWinner })
    }

    @Test
    fun `нажатие меняет один ответ и переключает один из нескольких до предела`() {
        val single = poll(mapOf("a" to listOf("@me:x")))
        assertEquals(listOf("b"), single.choiceAfterTapping("b"))
        assertNull(single.choiceAfterTapping("a"))

        val two = poll(mapOf("a" to listOf("@me:x")), max = 2)
        assertEquals(listOf("a", "b"), two.choiceAfterTapping("b"))
        assertEquals(emptyList<String>(), two.choiceAfterTapping("a"))

        val full = poll(mapOf("a" to listOf("@me:x"), "b" to listOf("@me:x")), max = 2)
        assertNull(full.choiceAfterTapping("c"))

        assertNull(poll(emptyMap(), ended = true).choiceAfterTapping("a"))
    }
}
