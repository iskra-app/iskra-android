package wtf.iskra.core.support

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountChoiceTest {
    @Test
    fun activeMeansOnlyTheOneOnScreen() {
        assertTrue(AccountChoice.includes(AccountChoice.ACTIVE, "a", active = "a"))
        assertFalse(AccountChoice.includes(AccountChoice.ACTIVE, "b", active = "a"))
    }

    @Test
    fun allMeansEveryone() {
        assertTrue(AccountChoice.includes(AccountChoice.ALL, "b", active = "a"))
    }

    @Test
    fun aSpecificAccountIgnoresWhichIsOpen() {
        assertTrue(AccountChoice.includes("b", "b", active = "a"))
        assertFalse(AccountChoice.includes("b", "a", active = "a"))
    }
}
