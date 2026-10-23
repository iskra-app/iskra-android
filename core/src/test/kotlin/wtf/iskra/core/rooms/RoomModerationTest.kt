package wtf.iskra.core.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Вся логика модерации: кого можно трогать и что можно выдать. Порт тестов `RoomDetailsStore`. */
class RoomModerationTest {
    private val admin = Authority.Level(100)
    private val moderator = Authority.Level(50)
    private val member = Authority.Level(0)

    private fun level(role: RoomMemberSummary.Role): Authority = when (role) {
        RoomMemberSummary.Role.ADMINISTRATOR -> admin
        RoomMemberSummary.Role.MODERATOR -> moderator
        RoomMemberSummary.Role.MEMBER -> member
    }

    @Test
    fun unlimitedOutranksEveryLevel() {
        assertTrue(Authority.Unlimited > Authority.Level(Long.MAX_VALUE))
        assertTrue(Authority.Level(1) < Authority.Unlimited)
        assertEquals(0, Authority.Unlimited.compareTo(Authority.Unlimited))
    }

    @Test
    fun youCannotManageYourselfOrAnEqual() {
        assertFalse(RoomModeration.canManage("@me:x", member, ownUserId = "@me:x", own = admin))
        assertFalse(RoomModeration.canManage("@other:x", admin, ownUserId = "@me:x", own = admin))
    }

    @Test
    fun youCanManageThoseBelow() {
        assertTrue(RoomModeration.canManage("@other:x", moderator, ownUserId = "@me:x", own = admin))
        assertTrue(RoomModeration.canManage("@other:x", member, ownUserId = "@me:x", own = moderator))
        assertFalse(RoomModeration.canManage("@other:x", moderator, ownUserId = "@me:x", own = moderator))
    }

    @Test
    fun aCreatorIsAboveEveryoneAndCannotBeTouched() {
        assertFalse(RoomModeration.canManage("@creator:x", Authority.Unlimited, ownUserId = "@me:x", own = admin))
        assertTrue(RoomModeration.canManage("@other:x", admin, ownUserId = "@creator:x", own = Authority.Unlimited))
    }

    @Test
    fun rolesAreGrantedUpToAndIncludingYourOwn() {
        assertEquals(
            listOf(RoomMemberSummary.Role.ADMINISTRATOR, RoomMemberSummary.Role.MODERATOR, RoomMemberSummary.Role.MEMBER),
            RoomModeration.rolesGrantableBy(admin, ::level),
        )
        assertEquals(
            listOf(RoomMemberSummary.Role.MODERATOR, RoomMemberSummary.Role.MEMBER),
            RoomModeration.rolesGrantableBy(moderator, ::level),
        )
        assertEquals(listOf(RoomMemberSummary.Role.MEMBER), RoomModeration.rolesGrantableBy(member, ::level))
    }

    @Test
    fun matrixIdIsAtNameColonServer() {
        assertTrue(RoomModeration.isMatrixId("@boris:matrix.org"))
        assertTrue(RoomModeration.isMatrixId("  @boris:matrix.org "))
        assertFalse(RoomModeration.isMatrixId("boris:matrix.org"))
        assertFalse(RoomModeration.isMatrixId("@boris"))
        assertFalse(RoomModeration.isMatrixId("@:matrix.org"))
        assertFalse(RoomModeration.isMatrixId("@boris:"))
        assertFalse(RoomModeration.isMatrixId("@boris:matrix .org"))
    }

    @Test
    fun anOpenRoomOffersFourHistoryChoicesAndAClosedOneThree() {
        assertEquals(4, HistoryAccess.choices(isPublic = true).size)
        assertEquals(listOf(HistoryAccess.SHARED, HistoryAccess.INVITED, HistoryAccess.JOINED), HistoryAccess.choices(isPublic = false))
    }

    @Test
    fun aThresholdIsReadAsTheLowestRoleThatClearsIt() {
        assertEquals(RoomMemberSummary.Role.MEMBER, RoomModeration.roleClearing(0, ::level))
        assertEquals(RoomMemberSummary.Role.MODERATOR, RoomModeration.roleClearing(50, ::level))
        assertEquals(RoomMemberSummary.Role.ADMINISTRATOR, RoomModeration.roleClearing(100, ::level))
    }

    @Test
    fun aThresholdBetweenRungsIsReadAsTheHigherOne() {
        // «Выгнать» на 75: модератору (50) нельзя, и говорить «модераторы» значило бы обещать то, в чём сервер откажет.
        assertEquals(RoomMemberSummary.Role.ADMINISTRATOR, RoomModeration.roleClearing(75, ::level))
        assertEquals(RoomMemberSummary.Role.MODERATOR, RoomModeration.roleClearing(25, ::level))
    }

    @Test
    fun aThresholdAboveEveryRungFallsBackToAdministrators() {
        assertEquals(RoomMemberSummary.Role.ADMINISTRATOR, RoomModeration.roleClearing(1000, ::level))
    }

    @Test
    fun anAdministratorMovesAnyRuleAndAModeratorOnlyThoseBelow() {
        assertEquals(3, RoomModeration.rolesForRule(100, admin, ::level).size)
        // Блокировка на 50 — модератору по плечу, а выше, на 100, нет.
        assertEquals(
            listOf(RoomMemberSummary.Role.MODERATOR, RoomMemberSummary.Role.MEMBER),
            RoomModeration.rolesForRule(50, moderator, ::level),
        )
        assertEquals(emptyList(), RoomModeration.rolesForRule(100, moderator, ::level))
        assertEquals(emptyList(), RoomModeration.rolesForRule(50, member, ::level))
    }
}
