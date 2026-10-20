package wtf.iskra.features.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Два правила, и оба о том, чтобы *не* сделать чего-то дважды: не открыть разговор, из которого уже
 * ушли, и не объявить сообщение, которое и так появляется перед глазами. Порт `PushRouterTests.swift`.
 */
class PushRouterTest {
    private val router = PushRouter()

    @Test
    fun tapNamesTheRoom() {
        router.tapped("!kitchen:matrix.org")
        assertEquals("!kitchen:matrix.org", router.request.value?.roomId)
        assertEquals("!kitchen:matrix.org", router.takeRequest()?.roomId)
    }

    /** Второй возврат — человек пришёл по своим делам; вернуть ему тот же чат — утащить обратно. */
    @Test
    fun theRoomIsTakenOnce() {
        router.tapped("!kitchen:matrix.org")
        router.takeRequest()
        assertNull(router.takeRequest())
        assertNull(router.request.value)
    }

    @Test
    fun anotherConversationIsNews() {
        router.visibleRoomId = "!kitchen:matrix.org"
        assertTrue(router.shouldPresent("!garden:matrix.org"))
    }

    @Test
    fun theVisibleRoomIsSilent() {
        router.visibleRoomId = "!kitchen:matrix.org"
        assertFalse(router.shouldPresent("!kitchen:matrix.org"))
    }

    @Test
    fun nothingOnScreenMeansEverythingShows() {
        assertTrue(router.shouldPresent("!kitchen:matrix.org"))
        // Пуш без комнаты показывается, а не проглатывается.
        assertTrue(router.shouldPresent(null))
    }

    @Test
    fun tapCarriesTheAccount() {
        router.tapped("!kitchen:matrix.org", "two")
        assertEquals(PushRouter.Request("!kitchen:matrix.org", "two"), router.takeRequest())
        assertNull(router.takeRequest())
    }

    /** Два человека на одном телефоне в одной комнате: у аккаунта не на экране сообщение — новость. */
    @Test
    fun anotherAccountsCopyOfTheRoomIsNews() {
        router.visibleRoomId = "!kitchen:matrix.org"
        router.visibleAccountId = "one"
        assertTrue(router.shouldPresent("!kitchen:matrix.org", "two"))
        assertFalse(router.shouldPresent("!kitchen:matrix.org", "one"))
    }
}
