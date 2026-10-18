package wtf.iskra.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.iskra.core.rooms.RoomKind

/** Порт `ThreadSettingsTests.swift` — без старого переключателя: на Android его не было. */
class ThreadSettingsTest {
    @Test
    fun byDefaultThreadsOnlyInRoomsOfOverAHundredAndRepliesStay() {
        val settings = ThreadSettings()
        assertEquals(ThreadSettings.Shown.IN_LARGE_ROOMS, settings.shown)
        assertFalse(settings.hidesReplies)
        assertFalse(settings.usesThreads(RoomKind.GROUP, 100))
        assertTrue(settings.usesThreads(RoomKind.GROUP, 101))
    }

    @Test
    fun aPersonalChatNeverHasThreadsEvenWithAlways() {
        assertFalse(ThreadSettings(ThreadSettings.Shown.ALWAYS).usesThreads(RoomKind.DIRECT, 2))
        assertTrue(ThreadSettings(ThreadSettings.Shown.ALWAYS).usesThreads(RoomKind.GROUP, 3))
        assertFalse(ThreadSettings(ThreadSettings.Shown.NEVER).usesThreads(RoomKind.GROUP, 500))
    }

    /** Ключи — те же, что у iOS: значения хранятся строками `Shown.rawValue`. */
    @Test
    fun storageNamesMatchIos() {
        assertEquals(listOf("always", "inLargeRooms", "never"), ThreadSettings.Shown.entries.map { it.storageName })
        assertEquals(ThreadSettings.Shown.NEVER, ThreadSettings.Shown.of("never"))
        assertEquals(null, ThreadSettings.Shown.of("nonsense"))
    }
}
