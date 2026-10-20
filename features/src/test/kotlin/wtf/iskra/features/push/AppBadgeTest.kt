package wtf.iskra.features.push

import org.junit.Assert.assertEquals
import org.junit.Test
import wtf.iskra.core.rooms.RoomKind
import wtf.iskra.core.rooms.RoomMembership
import wtf.iskra.core.rooms.RoomSummary

/**
 * Число непрочитанного. Закреплено, потому что его единственное в приложении можно проверить, сосчитав
 * самому, — и «три» над списком с двумя непрочитанными приходит в отчёте как «уведомления сломались».
 * Порт `AppBadgeTests.swift`.
 */
class AppBadgeTest {
    private fun room(
        id: String,
        unread: Int = 0,
        kind: RoomKind = RoomKind.DIRECT,
        membership: RoomMembership = RoomMembership.JOINED,
    ) = RoomSummary(id = id, name = id, kind = kind, unreadCount = unread, membership = membership)

    @Test
    fun nothingUnreadIsNoBadge() {
        assertEquals(0, AppBadge.count(emptyList()))
        assertEquals(0, AppBadge.count(listOf(room("!a"))))
    }

    @Test
    fun unreadMessagesAreSummed() {
        assertEquals(5, AppBadge.count(listOf(room("!a", unread = 2), room("!b", unread = 3))))
    }

    @Test
    fun invitationsCountOnce() {
        val invitation = room("!c", membership = RoomMembership.INVITED)
        assertEquals(1, AppBadge.count(listOf(invitation)))
        assertEquals(3, AppBadge.count(listOf(room("!a", unread = 2), invitation)))
    }

    @Test
    fun spacesAreNotCounted() {
        assertEquals(0, AppBadge.count(listOf(room("!s", unread = 7, kind = RoomKind.SPACE))))
    }
}
