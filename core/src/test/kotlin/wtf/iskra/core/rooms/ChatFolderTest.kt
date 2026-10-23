package wtf.iskra.core.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Какие чаты лежат в папке. Порт `ChatFolderTests`. */
class ChatFolderTest {
    private val dm = RoomSummary(id = "!dm", name = "Аня", kind = RoomKind.DIRECT)
    private val group = RoomSummary(id = "!group", name = "Семья", kind = RoomKind.GROUP, unreadMessageCount = 2, unreadCount = 2)
    private val muted = RoomSummary(id = "!muted", name = "Шум", kind = RoomKind.GROUP, isMuted = true)
    private val archived = RoomSummary(id = "!old", name = "Старое", kind = RoomKind.DIRECT, isLowPriority = true)
    private val space = RoomSummary(id = "!space", name = "Работа", kind = RoomKind.SPACE)

    private fun ids(rooms: List<RoomSummary>) = rooms.map { it.id }

    @Test
    fun kindsPickTheChatsInTheListsOrder() {
        val folder = ChatFolder(name = "Группы", kinds = setOf(ChatFolder.Kind.GROUPS))
        assertEquals(listOf("!group", "!muted"), ids(folder.rooms(listOf(dm, group, muted, space))))
    }

    @Test
    fun conditionsNarrowThem() {
        val all = listOf(dm, group, muted)
        assertEquals(
            listOf("!group"),
            ids(ChatFolder(name = "x", kinds = setOf(ChatFolder.Kind.DIRECT, ChatFolder.Kind.GROUPS), onlyUnread = true).rooms(all)),
        )
        assertEquals(listOf("!group"), ids(ChatFolder(name = "x", kinds = setOf(ChatFolder.Kind.GROUPS), excludesMuted = true).rooms(all)))
    }

    @Test
    fun theArchiveStaysOutUnlessAskedFor() {
        assertEquals(listOf("!dm"), ids(ChatFolder(name = "x", kinds = setOf(ChatFolder.Kind.DIRECT)).rooms(listOf(dm), listOf(archived))))
        assertEquals(
            listOf("!dm", "!old"),
            ids(ChatFolder(name = "x", kinds = setOf(ChatFolder.Kind.DIRECT), includesArchived = true).rooms(listOf(dm), listOf(archived))),
        )
    }

    @Test
    fun byHandBeatsTheKindsAndLeftOutBeatsEverything() {
        val folder = ChatFolder(name = "x", kinds = setOf(ChatFolder.Kind.GROUPS), included = listOf("!dm"), excluded = listOf("!group"))
        assertEquals(listOf("!dm", "!muted"), ids(folder.rooms(listOf(dm, group, muted))))
    }

    @Test
    fun aFolderWithNoRuleIsNotSaved() {
        assertFalse(ChatFolder(name = "x").isMeaningful)
        assertFalse(ChatFolder(name = " ", kinds = setOf(ChatFolder.Kind.DIRECT)).isMeaningful)
        assertTrue(ChatFolder(name = "x", included = listOf("!dm")).isMeaningful)
    }

    @Test
    fun theDocumentGoesRound() {
        val folders = listOf(
            ChatFolder(id = "a", name = "Личные", kinds = setOf(ChatFolder.Kind.DIRECT)),
            ChatFolder(id = "b", name = "Группы", kinds = setOf(ChatFolder.Kind.GROUPS), onlyUnread = true, included = listOf("!x")),
        )
        assertEquals(folders, ChatFolders.decode(ChatFolders.encode(folders)))
    }

    @Test
    fun aFolderFromANewerVersionIsSkippedNotTheList() {
        val mixed = """{"folders":[{"id":"a","name":"Личные","kinds":["direct"],"onlyUnread":false,"excludesMuted":false,"includesArchived":false,"included":[],"excluded":[]},{"id":"b","name":"?","kinds":["bots"]}]}"""
        assertEquals(listOf("a"), ChatFolders.decode(mixed).map { it.id })
    }

    @Test
    fun theIosDocumentIsRead() {
        // То, что Swift `Codable` пишет для папки: ключи и порядок видов не гарантированы, поля не пропущены.
        val ios = """{"folders":[{"excluded":["!y"],"includesArchived":true,"kinds":["groups","direct"],"id":"7","name":"Всё","included":[],"onlyUnread":false,"excludesMuted":true}]}"""
        val folder = ChatFolders.decode(ios).single()
        assertEquals(setOf(ChatFolder.Kind.DIRECT, ChatFolder.Kind.GROUPS), folder.kinds)
        assertTrue(folder.includesArchived && folder.excludesMuted)
        assertEquals(listOf("!y"), folder.excluded)
    }
}
