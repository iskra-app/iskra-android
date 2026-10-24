package wtf.iskra.features.push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortcutIdsTest {
    @Test
    fun roundTrip() {
        val id = ShortcutIds.room("@a:server", "!room:server")
        assertEquals("@a:server" to "!room:server", ShortcutIds.parse(id))
        assertEquals(true, id.startsWith(ShortcutIds.prefix("@a:server")))
    }

    @Test
    fun unnamedAccountComesBackNull() {
        assertEquals(null to "!r:s", ShortcutIds.parse(ShortcutIds.room(null, "!r:s")))
    }

    @Test
    fun foreignShortcutsAreNotOurs() {
        assertNull(ShortcutIds.parse(null))
        assertNull(ShortcutIds.parse("other/a/b"))
        assertNull(ShortcutIds.parse("room/a"))
        assertNull(ShortcutIds.parse("room/a/"))
    }
}
