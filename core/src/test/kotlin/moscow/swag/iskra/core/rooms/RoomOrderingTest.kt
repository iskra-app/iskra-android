package moscow.swag.iskra.core.rooms

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Порядок списка чатов: приглашения, закреплённые, потом новые сверху. Порт тестов `byRecency`. */
class RoomOrderingTest {
    private fun at(seconds: Long) = Instant.ofEpochSecond(seconds)

    @Test
    fun newestConversationFirst() {
        val rooms = listOf(
            RoomSummary(id = "old", name = "old", lastActivity = at(1)),
            RoomSummary(id = "new", name = "new", lastActivity = at(3)),
            RoomSummary(id = "mid", name = "mid", lastActivity = at(2)),
        )
        assertEquals(listOf("new", "mid", "old"), RoomOrdering.byRecency(rooms).map { it.id })
    }

    /** У приглашения нет сообщений — без этого правила оно утонуло бы под всеми разговорами. */
    @Test
    fun invitationsComeFirstEvenWithoutActivity() {
        val rooms = listOf(
            RoomSummary(id = "chat", name = "chat", lastActivity = at(10)),
            RoomSummary(id = "invite", name = "invite", membership = RoomMembership.INVITED),
        )
        assertEquals(listOf("invite", "chat"), RoomOrdering.byRecency(rooms).map { it.id })
    }

    @Test
    fun pinnedSitBelowInvitationsAndAboveTheRest() {
        val rooms = listOf(
            RoomSummary(id = "fresh", name = "fresh", lastActivity = at(10)),
            RoomSummary(id = "pinned", name = "pinned", lastActivity = at(1), isFavourite = true),
            RoomSummary(id = "invite", name = "invite", membership = RoomMembership.INVITED),
        )
        assertEquals(listOf("invite", "pinned", "fresh"), RoomOrdering.byRecency(rooms).map { it.id })
    }

    /** Ничьи держат порядок SDK — иначе пачка с одним временем тасовалась бы на каждой пересборке. */
    @Test
    fun tiesKeepTheSdkOrderAndEmptyRoomsSink() {
        val rooms = listOf(
            RoomSummary(id = "empty", name = "empty"),
            RoomSummary(id = "b", name = "b", lastActivity = at(5)),
            RoomSummary(id = "a", name = "a", lastActivity = at(5)),
        )
        assertEquals(listOf("b", "a", "empty"), RoomOrdering.byRecency(rooms).map { it.id })
    }

    @Test
    fun archivedRoomsGoToTheArchive() {
        val (active, archived) = RoomOrdering.partitionArchived(
            listOf(
                RoomSummary(id = "a", name = "a"),
                RoomSummary(id = "b", name = "b", isLowPriority = true),
            ),
        )
        assertEquals(listOf("a"), active.map { it.id })
        assertEquals(listOf("b"), archived.map { it.id })
    }
}
