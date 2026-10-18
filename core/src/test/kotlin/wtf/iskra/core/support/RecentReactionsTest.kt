package wtf.iskra.core.support

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReactionsTest {
    @Test
    fun newestFirstWithoutDuplicates() {
        val stored = RecentReactions.adding("🔥", RecentReactions.adding("👍", RecentReactions.adding("🔥", "")))
        assertEquals(listOf("🔥", "👍"), RecentReactions.list(stored))
    }

    @Test
    fun keepsTwelve() {
        val stored = (1..20).fold("") { all, n -> RecentReactions.adding("e$n", all) }
        assertEquals(RecentReactions.LIMIT, RecentReactions.list(stored).size)
        assertEquals("e20", RecentReactions.list(stored).first())
    }

    @Test
    fun sequencesWithJoinersSurvive() {
        assertEquals(listOf("❤️‍🔥", "👨‍👩‍👧"), RecentReactions.list(RecentReactions.adding("❤️‍🔥", "👨‍👩‍👧")))
    }
}
