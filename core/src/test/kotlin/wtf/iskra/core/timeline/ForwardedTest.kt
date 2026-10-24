package wtf.iskra.core.timeline

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForwardedTest {
    @Test
    fun readsTheMarkOffAnEvent() {
        val json = """{"content":{"body":"x","m.forwarded":{"sender":"@a:x","displayname":"Аня","origin_server_ts":1700000000000}}}"""
        val origin = Forwarded.origin(json)
        assertEquals("Аня", origin?.displayName)
        assertEquals(Instant.ofEpochMilli(1700000000000), origin?.timestamp)
    }

    @Test
    fun anOrdinaryEventIsNoForward() {
        assertNull(Forwarded.origin("""{"content":{"body":"x"}}"""))
        assertNull(Forwarded.origin("не json"))
    }

    @Test
    fun theNameFallsBackToTheMatrixId() {
        assertEquals("@a:x", ForwardOrigin(senderId = "@a:x").displayName)
    }

    @Test
    fun theCacheAnswersFromMemoryTheSecondTime() {
        var asked = 0
        val read = { asked++; """{"content":{"m.forwarded":{}}}""" }
        Forwarded.origin("\$e1", read)
        Forwarded.origin("\$e1", read)
        assertEquals(1, asked)
    }

    @Test
    fun aForwardCarriesOnlyWhenItWasFirstSaid() {
        val message = Message(
            id = "m", kind = MessageKind.Text("привет"), senderId = "@a:x", senderName = "Аня", senderAvatarUrl = null, isOwn = false,
            timestamp = Instant.ofEpochMilli(1000), delivery = null,
        )
        val content = Forwarded.content(message)!!
        assertTrue(content.contains("\"m.forwarded\""))
        assertTrue(content.contains("\"origin_server_ts\":1000"))
        assertTrue(!content.contains("@a:x"))
    }
}
