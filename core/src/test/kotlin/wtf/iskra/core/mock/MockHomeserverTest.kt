package wtf.iskra.core.mock

import wtf.iskra.core.stickers.StickerArt

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Демо-сервер без SDK: те ответы, на которых SDK входит и синхронизируется. Сквозной прогон с
 * настоящим SDK — инструментальный (`DemoBackendTest`), здесь — правила самого сервера.
 */
class MockHomeserverTest {
    private val server = MockHomeserver()

    private fun call(method: String, target: String, body: String = ""): Pair<Int, JSON> = runBlocking {
        val response = server.handle(HttpRequest(method, target, mapOf("Content-Type" to "application/json"), body.toByteArray()))
        response.status to JsonValues.decodeObject(response.body.toString(Charsets.UTF_8))
    }

    @Test
    fun versionsAdvertiseNativeSlidingSync() {
        val (status, body) = call("GET", "/_matrix/client/versions")
        assertEquals(200, status)
        assertEquals(true, body["unstable_features"].obj()?.get("org.matrix.simplified_msc3575"))
    }

    @Test
    fun passwordLoginAnswersWithTheDemoAccount() {
        val (status, body) = call(
            "POST", "/_matrix/client/v3/login",
            """{"type":"m.login.password","identifier":{"type":"m.id.user","user":"sasha"},"password":"demo"}""",
        )
        assertEquals(200, status, body.toString())
        assertEquals("@sasha:iskra.demo", body["user_id"])
    }

    @Test
    fun theFirstSyncCarriesEveryRoomTheAccountIsIn() {
        val (status, body) = call(
            "POST", "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync",
            """{"lists":{"all_rooms":{"ranges":[[0,99]],"timeline_limit":1}}}""",
        )
        assertEquals(200, status, body.toString())
        val rooms = body["rooms"].obj().orEmpty()
        // Девять комнат, где Саша есть или куда его позвали; «Офтоп» и «Swift» — чужие.
        assertEquals(9, rooms.size, rooms.keys.toString())
        assertTrue("!books:iskra.demo" in rooms)
        assertEquals(9, body["lists"].obj()?.get("all_rooms").obj()?.get("count").int())
        assertEquals("1", body["pos"])
    }

    @Test
    fun aSentMessageLandsAndTheNextSyncCarriesIt() {
        val (_, first) = call("POST", "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync", """{"lists":{}}""")
        server.replyDelay = kotlin.time.Duration.ZERO
        val (status, sent) = call("PUT", "/_matrix/client/v3/rooms/!notes:iskra.demo/send/m.room.message/t1", """{"msgtype":"m.text","body":"привет"}""")
        assertEquals(200, status)
        val (_, next) = call(
            "POST", "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync?pos=${first["pos"]}&timeout=0", """{"lists":{}}""",
        )
        val timeline = next["rooms"].obj()?.get("!notes:iskra.demo").obj()?.get("timeline").list().orEmpty()
        assertEquals(sent["event_id"], timeline.single().obj()?.get("event_id"))
        // Повтор той же транзакции — то же событие, а не второе.
        val (_, again) = call("PUT", "/_matrix/client/v3/rooms/!notes:iskra.demo/send/m.room.message/t1", """{"msgtype":"m.text","body":"привет"}""")
        assertEquals(sent["event_id"], again["event_id"])
    }

    @Test
    fun historyPagesBackwardsNewestFirst() {
        val (_, page) = call("GET", "/_matrix/client/v3/rooms/!iskra-dev:iskra.demo/messages?dir=b&limit=5")
        val chunk = page["chunk"].list().orEmpty().mapNotNull { it.obj() }
        assertEquals(5, chunk.size)
        val stamps = chunk.mapNotNull { it["origin_server_ts"].long() }
        assertEquals(stamps.sortedDescending(), stamps)
        assertTrue(page["end"].str()!!.startsWith("t"))
    }

    @Test
    fun anUnknownEndpointIsWrittenDown() {
        val (status, _) = call("GET", "/_matrix/client/v3/nothing_here")
        assertEquals(404, status)
        assertEquals(listOf("GET _matrix/client/v3/nothing_here"), server.unrecognized)
    }

    @Test
    fun theStickersCameFromIos() {
        StickerArt.entries.forEach { assertTrue(it.png().size > 1000, "${it.shortcode}.png пуст") }
    }

    @Test
    fun aDrawnPictureIsAPng() {
        val png = MockMedia.avatar(0.5)
        assertEquals(listOf(0x89, 'P'.code, 'N'.code, 'G'.code), png.take(4).map { it.toInt() and 0xFF })
    }
}
