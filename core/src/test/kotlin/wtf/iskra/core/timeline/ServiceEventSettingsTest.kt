package wtf.iskra.core.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceEventSettingsTest {
    private val who = listOf(RoomEventPerson("@a:s", "Аня"))

    @Test
    fun nothingIsHiddenByDefault() {
        val settings = ServiceEventSettings()
        assertFalse(settings.hides(RoomEventKind.Joined(who)))
        assertFalse(settings.hides(RoomEventKind.RenamedThemselves("А", "Б")))
    }

    @Test
    fun membershipAndNamesAreHiddenSeparately() {
        val membership = ServiceEventSettings(hidesMembershipChanges = true)
        assertTrue(membership.hides(RoomEventKind.Joined(who)))
        assertTrue(membership.hides(RoomEventKind.Banned(who)))
        assertFalse(membership.hides(RoomEventKind.RenamedThemselves("А", "Б")))
        val names = ServiceEventSettings(hidesNameChanges = true)
        assertTrue(names.hides(RoomEventKind.RenamedThemselves("А", "Б")))
        assertFalse(names.hides(RoomEventKind.Left(who)))
    }

    @Test
    fun theRoomsOwnHistoryIsNeverHidden() {
        val all = ServiceEventSettings(hidesNameChanges = true, hidesMembershipChanges = true)
        for (kind in listOf(RoomEventKind.RoomRenamed("х"), RoomEventKind.RoomTopicChanged, RoomEventKind.RoomPictureChanged, RoomEventKind.RoomCreated, RoomEventKind.EncryptionEnabled)) {
            assertEquals(false, all.hides(kind))
        }
    }
}
