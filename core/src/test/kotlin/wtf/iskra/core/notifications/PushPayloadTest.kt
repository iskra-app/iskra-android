package wtf.iskra.core.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оболочка пуша: что записано в pusher при регистрации и что приезжает обратно. Порт
 * `PushPayloadTests.swift` — без `aps`, которого Android не нужно, — и своё: разбор того, что приносят
 * оба транспорта. Сбой здесь — *тишина*: шлюз отвечает 200, а на телефоне ничего.
 */
class PushPayloadTest {
    @Test
    fun thePayloadCarriesNoContent() {
        val json = PushRegistration.defaultPayload("3F2A-random")
        // Одно и то же для каждого пуша: что-то конкретное в нём было бы фактом об одном сообщении.
        assertFalse(json.contains("room"))
        assertFalse(json.contains("event"))
        assertFalse(json.contains("sender"))
    }

    /** Чей пуш — случайным ID сессии, никогда не Matrix ID: шлюз и Google не узнают, кто здесь вошёл. */
    @Test
    fun thePayloadNamesTheAccount() {
        val json = PushRegistration.defaultPayload("3F2A-random")
        val payload = Json.parseToJsonElement(json).jsonObject
        assertEquals("3F2A-random", payload[PushRegistration.ACCOUNT_KEY]?.jsonPrimitive?.content)
        assertFalse(json.contains("@"))
    }

    /** Строка для шлюза — не `applicationId`: в нём дефис запрещён (PROMPT 10.2). */
    @Test
    fun theGatewayKnowsTheAppByItsOwnName() {
        assertEquals("wtf.iskra-android", PushRegistration.APP_ID)
    }

    // FCM: gcm-pushkin Sygnal кладёт default_payload плоско рядом с room_id и event_id, всё строками.

    @Test
    fun anFcmMessageSaysWhereAndWhose() {
        val payload = PushPayload.fromFcm(
            mapOf(
                "event_id" to "\$event",
                "room_id" to "!room:matrix.org",
                "unread" to "3",
                "prio" to "high",
                "iskra_account" to "3F2A-random",
            ),
        )
        assertEquals(PushPayload("!room:matrix.org", "\$event", "3F2A-random", 3), payload)
        assertTrue(payload.isMessage)
    }

    /** Только счётчик — так сервер говорит «прочитано на другом устройстве». Не сообщение. */
    @Test
    fun aCountsOnlyFcmMessageIsNotAMessage() {
        val payload = PushPayload.fromFcm(mapOf("unread" to "0", "iskra_account" to "a"))
        assertFalse(payload.isMessage)
        assertEquals(0, payload.unread)
    }

    /** Старый pusher без `default_payload`: аккаунт выбирает декодер, перебором. */
    @Test
    fun anOldPusherDoesNotNameTheAccount() {
        assertNull(PushPayload.fromFcm(mapOf("event_id" to "\$e", "room_id" to "!r")).accountId)
    }

    // UnifiedPush: дистрибьютор передаёт тело запроса Matrix-шлюза как есть.

    @Test
    fun aMatrixNotifyBodySaysWhereAndWhose() {
        val body = """
            {"notification":{"event_id":"${'$'}event","room_id":"!room:matrix.org","prio":"high",
             "counts":{"unread":2},
             "devices":[{"app_id":"wtf.iskra-android","pushkey":"https://ntfy.sh/upAbc?up=1",
               "data":{"format":"event_id_only","default_payload":{"iskra_account":"3F2A-random"}}}]}}
        """.trimIndent()
        assertEquals(PushPayload("!room:matrix.org", "\$event", "3F2A-random", 2), PushPayload.fromMatrixNotify(body))
    }

    @Test
    fun aMatrixNotifyWithoutDevicesStillRoutes() {
        val payload = PushPayload.fromMatrixNotify("""{"notification":{"event_id":"${'$'}e","room_id":"!r"}}""")
        assertEquals(PushPayload("!r", "\$e", null, null), payload)
    }

    /** От дистрибьютора может прийти что угодно; чужое отбрасывается молча, а не роняет обработчик. */
    @Test
    fun somethingElseIsNotAPush() {
        assertNull(PushPayload.fromMatrixNotify("not json"))
        assertNull(PushPayload.fromMatrixNotify("""{"hello":"world"}"""))
        assertNull(PushPayload.fromMatrixNotify("[1,2]"))
    }
}
