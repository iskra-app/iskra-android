package wtf.iskra.core.rooms

import org.matrix.rustcomponents.sdk.PublicRoomJoinRule
import org.matrix.rustcomponents.sdk.RoomDescription
import org.matrix.rustcomponents.sdk.RoomDirectorySearchEntryUpdate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationsTest {
    private fun contact(name: String) = Contact("@${name.lowercase()}:example.org", name, null, "!$name:example.org")

    private fun description(id: String, rule: PublicRoomJoinRule? = PublicRoomJoinRule.PUBLIC) = RoomDescription(
        roomId = id,
        name = "Комната $id",
        topic = "  о чём-то \n",
        alias = "#$id:example.org",
        avatarUrl = null,
        joinRule = rule,
        isWorldReadable = false,
        joinedMembers = 7uL,
    )

    @Test
    fun contactsAreGroupedByFirstLetterAndWhatIsNotALetterGoesLast() {
        val groups = Contact.byLetter(listOf("Аня", "алёна", "Boris", "42", "🙂 smile", "Bella").map(::contact))

        assertEquals("#", groups.last().first)
        assertEquals(listOf("алёна", "Аня"), groups.first { it.first == "А" }.second.map { it.name })
        assertEquals(listOf("Bella", "Boris"), groups.first { it.first == "B" }.second.map { it.name })
        assertEquals(2, groups.first { it.first == "#" }.second.size)
    }

    @Test
    fun aRoomAddressIsAnAliasOrARoomId() {
        assertTrue(Conversations.isRoomAddress("#matrix:matrix.org"))
        assertTrue(Conversations.isRoomAddress(" #matrix:matrix.org:8448 "))
        assertTrue(Conversations.isRoomAddress("!abc:matrix.org"))
        // Версия комнаты 12: сервера в ID нет вовсе.
        assertTrue(Conversations.isRoomAddress("!31hneApxJ_1o-63DmFrpeqnkFfWppnzWso1JvH3ogLM"))

        assertFalse(Conversations.isRoomAddress("#matrix"))
        assertFalse(Conversations.isRoomAddress("#:matrix.org"))
        assertFalse(Conversations.isRoomAddress("matrix:matrix.org"))
        assertFalse(Conversations.isRoomAddress("@boris:matrix.org"))
        assertFalse(Conversations.isRoomAddress("#my room:matrix.org"))
        assertFalse(Conversations.isRoomAddress("!"))
    }

    @Test
    fun aPersonWithoutANameIsCalledByTheirAddress() {
        assertEquals("@a:b.c", PersonProfile("@a:b.c", null, null).name)
        assertEquals("@a:b.c", PersonProfile("@a:b.c", "  ", null).name)
        assertEquals("Аня", PersonProfile("@a:b.c", "Аня", null).name)
    }

    @Test
    fun onlyAnOpenRoomIsJoinableWithATap() {
        assertTrue(PublicRoom(description("a")).isOpen)
        // Ни одного правила — так старые серверы показывают открытую комнату.
        assertTrue(PublicRoom(description("b", rule = null)).isOpen)
        assertFalse(PublicRoom(description("c", rule = PublicRoomJoinRule.KNOCK)).isOpen)
        assertFalse(PublicRoom(description("d", rule = PublicRoomJoinRule.INVITE)).isOpen)
    }

    @Test
    fun aDirectoryRowIsReadAsItIsTitledAndCounted() {
        val room = PublicRoom(description("a"))
        assertEquals("Комната a", room.title)
        assertEquals("о чём-то", room.topic)
        assertEquals(7, room.memberCount)
        assertEquals("#a:example.org", PublicRoom(description("a").copy(name = "")).title)
    }

    @Test
    fun directoryDiffsAreAppliedInOrder() {
        val first = PublicRoomDirectory.apply(emptyList(), listOf(RoomDirectorySearchEntryUpdate.Append(listOf(description("a"), description("b")))))
        assertEquals(listOf("a", "b"), first.map { it.id })

        val second = PublicRoomDirectory.apply(
            first,
            listOf(
                RoomDirectorySearchEntryUpdate.PushFront(description("z")),
                RoomDirectorySearchEntryUpdate.Remove(2u),
                RoomDirectorySearchEntryUpdate.Insert(1u, description("m")),
            ),
        )
        assertEquals(listOf("z", "m", "a"), second.map { it.id })

        val third = PublicRoomDirectory.apply(second, listOf(RoomDirectorySearchEntryUpdate.Truncate(1u)))
        assertEquals(listOf("z"), third.map { it.id })
        assertEquals(emptyList(), PublicRoomDirectory.apply(third, listOf(RoomDirectorySearchEntryUpdate.Clear)))
    }
}
