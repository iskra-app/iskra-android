package wtf.iskra.core.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Что строка списка выводит из счётчиков SDK: число, точку, реакции и цвет значка. */
class RoomSummaryTest {
    private fun room(
        unread: Int = 0,
        messages: Int = 0,
        mentions: Int = 0,
        markedUnread: Boolean = false,
        muted: Boolean = false,
    ) = RoomSummary(
        id = "!r:x",
        name = "r",
        unreadCount = unread,
        unreadMessageCount = messages,
        mentionCount = mentions,
        isMarkedUnread = markedUnread,
        isMuted = muted,
    )

    @Test
    fun anOrdinaryUnreadMessageIsANumber() {
        val summary = room(unread = 2, messages = 2)
        assertTrue(summary.hasUnread)
        assertTrue(summary.hasUnreadCount)
        assertEquals(2, summary.unreadNotifyingMessages)
        assertFalse(summary.hasUnreadReactions)
    }

    /** Реакция уведомляет, но сообщением не считается: число на значке её не включает. */
    @Test
    fun reactionsAreToldApartFromMessages() {
        val summary = room(unread = 3, messages = 1)
        assertEquals(2, summary.unreadReactionCount)
        assertEquals(1, summary.unreadNotifyingMessages)
        assertTrue(summary.hasUnreadReactions)
    }

    @Test
    fun reactionsAloneAreNotSomethingToRead() {
        val summary = room(unread = 1, messages = 0)
        assertFalse(summary.hasUnread)
        assertTrue(summary.hasUnreadReactions)
    }

    /** Беззвучный чат не уведомляет — у него непрочитанное без числа, то есть точка. */
    @Test
    fun aMutedRoomHasADotNotANumber() {
        val summary = room(unread = 0, messages = 4, muted = true)
        assertTrue(summary.hasUnread)
        assertFalse(summary.hasUnreadCount)
        assertEquals(UnreadEmphasis.MUTED, summary.unreadEmphasis)
    }

    /** Помеченный вручную — тоже точка, но не серая: его никто не глушил. */
    @Test
    fun markedUnreadIsADotInTheAccent() {
        val summary = room(markedUnread = true)
        assertTrue(summary.hasUnread)
        assertFalse(summary.hasUnreadCount)
        assertEquals(UnreadEmphasis.ORDINARY, summary.unreadEmphasis)
    }

    @Test
    fun aMentionIsSaidBesideTheBadge() {
        assertTrue(room(unread = 1, messages = 1, mentions = 1).isMentioned)
        assertFalse(room(unread = 1, messages = 1).isMentioned)
    }

    @Test
    fun theSeedIsTheRoomUnlessToldOtherwise() {
        assertEquals("!r:x", room().avatarSeed)
    }
}
