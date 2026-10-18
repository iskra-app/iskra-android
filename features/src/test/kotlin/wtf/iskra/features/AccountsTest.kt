package wtf.iskra.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.iskra.core.security.AccountRecord
import wtf.iskra.features.settings.accountName

/** Что видит переключатель аккаунтов: кто «другие» и как их назвать. */
class AccountsTest {
    private val sasha = AccountRecord("s1", "@sasha:matrix.org", displayName = "Саша")
    private val work = AccountRecord("s2", "@a.petrov:work.example", displayName = "  ")

    @Test
    fun othersAreEveryoneButTheAccountOnScreen() {
        val accounts = RootModel.Accounts(all = listOf(sasha, work), activeId = "s1", isAvailable = true)
        assertEquals(listOf(work), accounts.others)
        assertTrue(accounts.hasSeveral)
    }

    @Test
    fun oneAccountIsNotSeveral() {
        assertFalse(RootModel.Accounts(all = listOf(sasha), activeId = "s1").hasSeveral)
    }

    /** Пустое имя — не имя: строка без подписи хуже Matrix ID. */
    @Test
    fun anAccountIsCalledByItsNameOrItsMatrixId() {
        assertEquals("Саша", accountName(sasha))
        assertEquals("@a.petrov:work.example", accountName(work))
    }
}
