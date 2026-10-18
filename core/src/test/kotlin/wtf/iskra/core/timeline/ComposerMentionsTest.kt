package wtf.iskra.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Порт `ComposerMentionsTests.swift`: когда «@» спрашивает, кого находит и что уходит. */
class ComposerMentionsTest {
    private fun member(id: String, name: String) = MentionCandidate(id, name, null)
    private fun query(text: String) = ComposerMentions.query(text, text.length)

    @Test
    fun anAtAtTheStartOrAfterASpaceIsAsking() {
        assertEquals("", query("@")?.text)
        assertEquals("ка", query("привет @ка")?.text)
        assertEquals("kai", query("(@kai")?.text)
    }

    @Test
    fun anAddressIsNotAMention() {
        assertNull(query("boris@mail"))
    }

    @Test
    fun aSpaceEndsTheQuestion() {
        assertNull(query("@kai "))
        assertNull(query("@kai guo"))
    }

    @Test
    fun theQueryIsWhereTheCursorIs() {
        assertEquals("kai", ComposerMentions.query("@kai and more", 4)?.text)
    }

    @Test
    fun anyWordOfTheNameOrTheIdMatches() {
        val members = listOf(member("@kai:matrix.org", "Kai Guo"), member("@ezh:matrix.org", "Ёжик"), member("@mx:matrix.org", "Émile"))
        assertEquals(listOf("@kai:matrix.org"), ComposerMentions.matches("gu", members).map { it.userId })
        assertEquals(listOf("@kai:matrix.org"), ComposerMentions.matches("KAI", members).map { it.userId })
        assertEquals(listOf("@ezh:matrix.org"), ComposerMentions.matches("еж", members).map { it.userId })
        assertEquals(listOf("@mx:matrix.org"), ComposerMentions.matches("emi", members).map { it.userId })
        assertEquals(listOf("@mx:matrix.org"), ComposerMentions.matches("mx", members).map { it.userId })
        assertEquals(3, ComposerMentions.matches("", members).size)
    }

    @Test
    fun pickingPutsTheNameInWithASpace() {
        val text = "hi @ka"
        val (result, cursor, mention) = ComposerMentions.insert(member("@kai:m.org", "Kai Guo"), query(text)!!, text)
        assertEquals("hi @Kai Guo ", result)
        assertEquals(result.length, cursor)
        assertEquals(ComposerMention("@kai:m.org", "Kai Guo"), mention)
    }

    @Test
    fun aSurvivingMentionBecomesALink() {
        val (markdown, ids) = ComposerMentions.markdown("hi @Kai Guo, see you", listOf(ComposerMention("@kai:m.org", "Kai Guo")))
        assertEquals("hi [@Kai Guo](https://matrix.to/#/@kai:m.org), see you", markdown)
        assertEquals(listOf("@kai:m.org"), ids)
    }

    @Test
    fun aDeletedMentionPingsNobody() {
        val (markdown, ids) = ComposerMentions.markdown("hi Ka", listOf(ComposerMention("@kai:m.org", "Kai")))
        assertEquals("hi Ka", markdown)
        assertTrue(ids.isEmpty())
    }

    @Test
    fun aShortNameIsNotFoundInsideALongerOne() {
        val mentions = listOf(ComposerMention("@ann:m.org", "Ann"), ComposerMention("@anna:m.org", "Anna"))
        val (markdown, ids) = ComposerMentions.markdown("@Anna", mentions)
        assertEquals("[@Anna](https://matrix.to/#/@anna:m.org)", markdown)
        assertEquals(listOf("@anna:m.org"), ids)
    }

    @Test
    fun markupInANameIsEscapedAndComesBack() {
        val mentions = listOf(ComposerMention("@x:m.org", "*star* [x]"))
        val (markdown, _) = ComposerMentions.markdown("@*star* [x] hi", mentions)
        assertEquals("""[@\*star\* \[x\]](https://matrix.to/#/@x:m.org) hi""", markdown)
        val (text, back) = ComposerMentions.unlinked(markdown)
        assertEquals("@*star* [x] hi", text)
        assertEquals(mentions, back)
    }
}
