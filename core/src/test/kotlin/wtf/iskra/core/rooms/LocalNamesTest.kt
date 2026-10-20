package wtf.iskra.core.rooms

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Прозвища личных чатов. Порт `LocalNamesTests.swift`; два теста о пуше оттуда — в `PushMapperTest`.
 */
class LocalNamesTest {
    private class Memory : LocalNames.Storage {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) {
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private val storage = Memory()
    private val names = LocalNames(storage)

    @Test
    fun roundTrips() {
        names.set("test", "!a", "Мама")
        assertEquals("Мама", names.name("test", "!a"))
        assertNull(names.name("test", "!b"))
    }

    @Test
    fun blankIsAbsence() {
        names.set("test", "!a", "Мама")
        names.set("test", "!a", "   ")
        assertNull(names.name("test", "!a"))
        names.set("test", "!a", "Мама")
        names.set("test", "!a", null)
        assertNull(names.name("test", "!a"))
    }

    @Test
    fun trims() {
        names.set("test", "!a", "  Аня ")
        assertEquals("Аня", names.name("test", "!a"))
    }

    @Test
    fun announcesOnlyRealChanges() = runTest(UnconfinedTestDispatcher()) {
        val heard = mutableListOf<LocalNames.Change>()
        val listening = launch { names.changes.toList(heard) }
        names.set("test", "!a", "Мама")
        names.set("test", "!a", "Мама")
        names.set("test", "!a", "Мама ")
        assertEquals(1, heard.size)
        names.set("test", "!a", null)
        assertEquals(2, heard.size)
        listening.cancel()
    }

    @Test
    fun forgetsEverything() {
        names.set("test", "!a", "Мама")
        names.set("test", "!b", "Аня")
        names.forget("test")
        assertNull(names.name("test", "!a"))
        assertNull(names.name("test", "!b"))
    }

    /** Своё, не из iOS: таблицы аккаунтов не смешиваются — два человека на одном телефоне. */
    @Test
    fun accountsKeepTheirOwnNames() {
        names.set("one", "!a", "Мама")
        assertNull(names.name("two", "!a"))
        names.forget("two")
        assertEquals("Мама", names.name("one", "!a"))
    }
}
