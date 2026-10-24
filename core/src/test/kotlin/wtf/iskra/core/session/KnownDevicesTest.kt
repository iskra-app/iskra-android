package wtf.iskra.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Что считается новым входом. Баннер над списком говорит «в вашем аккаунте мог быть кто-то», и ошибиться можно двояко, оба плохо: кричать «волки» над сеансами,
 * что были всегда, и молчать о новом. Порт `KnownDevicesTests`.
 */
class KnownDevicesTest {
    private class Memory : KnownDevices.Store {
        private val values = HashMap<String, Set<String>>()
        override fun get(account: String) = values[account]
        override fun put(account: String, fingerprints: Set<String>?) {
            if (fingerprints == null) values.remove(account) else values[account] = fingerprints
        }
    }

    private fun fresh() = KnownDevices(Memory())

    private fun device(id: String, name: String? = null, current: Boolean = false) = AccountDevice(id, name, null, null, current)

    @Test
    fun theFirstLookAsksAboutNothingAndRemembersEverything() {
        val known = fresh()
        val list = listOf(device("ME", current = true), device("OLD"))
        assertTrue(known.newSessions(list, "A").isEmpty())
        assertTrue(known.newSessions(list, "A").isEmpty())
    }

    @Test
    fun aSessionThatAppearsLaterIsNewUntilConfirmed() {
        val known = fresh()
        known.newSessions(listOf(device("ME", current = true), device("OLD")), "A")
        val list = listOf(device("ME", current = true), device("OLD"), device("STRANGER"))
        assertEquals(listOf("STRANGER"), known.newSessions(list, "A").map { it.id })
        assertEquals(listOf("STRANGER"), known.newSessions(list, "A").map { it.id })
        known.confirm(device("STRANGER"), "A")
        assertTrue(known.newSessions(list, "A").isEmpty())
    }

    @Test
    fun thisDeviceIsNeverNewsEvenWhenItWasNotKnown() {
        val known = fresh()
        known.newSessions(listOf(device("OLD")), "A")
        assertTrue(known.newSessions(listOf(device("ME", current = true), device("OLD")), "A").isEmpty())
    }

    @Test
    fun eachAccountKeepsItsOwnMemoryAndForgettingOneLeavesTheOther() {
        val known = fresh()
        known.newSessions(listOf(device("X")), "A")
        // У B на первый взгляд тихо тоже, что бы ни знал A.
        assertTrue(known.newSessions(listOf(device("Y")), "B").isEmpty())
        known.forget("A")
        assertTrue(known.newSessions(listOf(device("Z")), "A").isEmpty())
        assertEquals(listOf("W"), known.newSessions(listOf(device("Y"), device("W")), "B").map { it.id })
    }

    @Test
    fun aKnownIdWithADifferentNameIsNewBecauseAClientChoosesItsOwnDeviceId() {
        val known = fresh()
        known.newSessions(listOf(device("ME", current = true), device("PHONE", name = "Iskra (iPhone)")), "A")
        val list = listOf(device("ME", current = true), device("PHONE", name = "Element Web: Firefox"))
        assertEquals(listOf("PHONE"), known.newSessions(list, "A").map { it.id })
    }

    @Test
    fun aSessionThatWentAwayIsForgotten() {
        val known = fresh()
        known.newSessions(listOf(device("ME", current = true), device("GONE")), "A")
        known.newSessions(listOf(device("ME", current = true)), "A")
        // Вернулся тем же ID и именем — это уже новое.
        assertEquals(listOf("GONE"), known.newSessions(listOf(device("ME", current = true), device("GONE")), "A").map { it.id })
    }
}
