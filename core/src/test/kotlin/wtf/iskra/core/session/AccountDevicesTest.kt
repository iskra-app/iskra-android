package wtf.iskra.core.session

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Settings → Devices: сессии аккаунта, прочитанные с сервера и законченные — с паролем аккаунта там, где сервер его просит,
 * и на странице сервера там, где сессии — его. Порт `AccountDevicesTests`.
 */
class AccountDevicesTest {
    @Test
    fun thisDeviceFirstThenTheRestByHowRecentlySeen() {
        val body = """{"devices":[
            {"device_id":"OLD","display_name":"Element Web: Firefox on Linux","last_seen_ts":1000},
            {"device_id":"ME","display_name":"Iskra/1.0 (iPhone 16 Pro; iOS 26.0)","last_seen_ts":500},
            {"device_id":"NEW","last_seen_ts":9000,"last_seen_ip":"198.51.100.7"}]}"""
        val devices = AccountDevices.devices(body, "ME")
        assertEquals(listOf("ME", "NEW", "OLD"), devices.map { it.id })
        assertTrue(devices[0].isCurrent)
        assertEquals("198.51.100.7", devices[1].lastSeenIp)
        assertEquals(9000L, devices[1].lastSeenAt)
    }

    @Test
    fun userAgentNameIsHardwareOverAppAndSystem() {
        val device = AccountDevice("A", "Iskra/1.0 (iPhone 16 Pro; iOS 26.0)", null, null, true)
        assertEquals("iPhone 16 Pro", device.title)
        assertEquals("Iskra 1.0 · iOS 26.0", device.detail)
        assertEquals(DevicePlatform.IPHONE, device.platform)
    }

    @Test
    fun androidNameThisAppSends() {
        val device = AccountDevice("A", "Iskra/0.1 (Pixel 9; Android 16)", null, null, true)
        assertEquals("Pixel 9", device.title)
        assertEquals(DevicePlatform.ANDROID, device.platform)
    }

    @Test
    fun appColonDeviceNameIsDeviceOverApp() {
        val device = AccountDevice("B", "Element Web: Firefox on Linux", null, null, false)
        assertEquals("Firefox on Linux", device.title)
        assertEquals("Element Web", device.detail)
        assertEquals(DevicePlatform.WEB, device.platform)
    }

    @Test
    fun nameNobodyParsesIsShownWholeAndNoNameIsTheDeviceId() {
        val named = AccountDevice("C", "my laptop", null, null, false)
        assertEquals("my laptop", named.title)
        assertNull(named.detail)
        val unnamed = AccountDevice("DEVICEID", null, null, null, false)
        assertEquals("DEVICEID", unnamed.title)
        assertEquals(DevicePlatform.UNKNOWN, unnamed.platform)
    }

    @Test
    fun whatADeleteAnsweredInTheScreensTerms() {
        val password = """{"session":"s","flows":[{"stages":["m.login.password"]}]}"""
        val sso = """{"session":"s","flows":[{"stages":["m.login.sso"]}]}"""
        assertNull(AccountDevices.outcome(200, "{}", false))
        assertEquals(AccountDevicesError.NeedsPassword, AccountDevices.outcome(401, password, false))
        assertEquals(AccountDevicesError.WrongPassword, AccountDevices.outcome(401, """{"errcode":"M_FORBIDDEN"}""", true))
        assertEquals(AccountDevicesError.ManagedByServer(null), AccountDevices.outcome(401, sso, false))
        assertEquals(AccountDevicesError.ManagedByServer(null), AccountDevices.outcome(404, """{"errcode":"M_UNRECOGNIZED"}""", false))
        assertEquals(AccountDevicesError.Failed, AccountDevices.outcome(500, "{}", false))
    }

    @Test
    fun endingAsksWithoutPasswordThenWithItInTheSameAuthSession() = runBlocking {
        val sent = mutableListOf<HomeserverRequest>()
        val source = AccountDevices(
            userId = "@me:example.org",
            currentDeviceId = "ME",
            isManagedByServer = false,
            send = { request ->
                sent += request
                val body = request.body?.let { Json.parseToJsonElement(it).jsonObject }
                if (body?.get("auth") == null) {
                    HomeserverResponse(401, """{"session":"uia-1","flows":[{"stages":["m.login.password"]}]}""")
                } else {
                    HomeserverResponse(200, "{}")
                }
            },
            serverPage = { null },
        )
        try {
            source.end(listOf("OTHER"))
            fail("пароль должен был понадобиться")
        } catch (error: AccountDevicesException) {
            assertEquals(AccountDevicesError.NeedsPassword, error.error)
        }
        source.end(listOf("OTHER", "ME"), password = "hunter2")
        val body = Json.parseToJsonElement(sent.last().body!!).jsonObject
        // Это устройство никто не просит закончить себя: это выход, и живёт он в другом месте.
        assertEquals(listOf("OTHER"), (body["devices"] as JsonArray).map { it.jsonPrimitive.content })
        val auth = body["auth"] as JsonObject
        assertEquals("uia-1", auth["session"]?.jsonPrimitive?.contentOrNull)
        assertEquals("m.login.password", auth["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun accountSignedInThroughTheBrowserIsSentToTheServersPage() = runBlocking {
        val page = "https://account.example.org/sessions/OTHER"
        val source = AccountDevices(
            userId = "@me:example.org",
            currentDeviceId = "ME",
            isManagedByServer = true,
            send = { fail("ничего не должно уходить"); HomeserverResponse(500, "") },
            serverPage = { id -> if (id == "OTHER") page else null },
        )
        try {
            source.end(listOf("OTHER"))
            fail("должна была открыться страница сервера")
        } catch (error: AccountDevicesException) {
            assertEquals(AccountDevicesError.ManagedByServer(page), error.error)
        }
    }
}
