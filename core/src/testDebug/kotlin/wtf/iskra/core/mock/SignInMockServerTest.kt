package wtf.iskra.core.mock

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сервер входа сам по себе, без SDK: его маршруты отвечают то, что SDK потом прочтёт. Вход через
 * настоящий SDK — инструментальный `SignInMockServerSdkTest` (порт `SignInMockServerTests.swift`):
 * `.so` SDK есть только на устройстве.
 */
class SignInMockServerTest {
    private fun call(server: SignInMockServer, method: String, target: String, body: String = "", token: String? = null): Pair<Int, Any?> =
        runBlocking {
            val headers = token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
            val response = server.handle(HttpRequest(method, target, headers, body.toByteArray()))
            val text = response.body.decodeToString()
            response.status to (if (text.startsWith("{")) JsonValues.decodeObject(text) else text)
        }

    private fun location(server: SignInMockServer, target: String): String? = runBlocking {
        server.handle(HttpRequest("GET", target, emptyMap(), ByteArray(0))).headers["Location"]
    }

    private fun signIn(server: SignInMockServer, name: String = "alice", password: String = "password"): Pair<Int, JSON> {
        val (status, body) = call(
            server, "POST", "/_matrix/client/v3/login",
            """{"type":"m.login.password","identifier":{"type":"m.id.user","user":"$name"},"password":"$password"}""",
        )
        return status to (body as JSON)
    }

    @Test
    fun discoveryOffersEveryWayInItWasToldTo() {
        val server = SignInMockServer()
        val flows = (call(server, "GET", "/_matrix/client/v3/login").second as JSON)["flows"].list()
        assertEquals(listOf("m.login.password"), flows?.map { it.obj()?.get("type") })
        val metadata = call(server, "GET", "/_matrix/client/v1/auth_metadata").second as JSON
        assertEquals(listOf("create"), metadata["prompt_values_supported"])
    }

    @Test
    fun aPasswordOnlyServerOffersNoOAuth() {
        val server = SignInMockServer(SignInMockServer.Configuration(offersOAuth = false, offersRegistration = false))
        assertEquals(404, call(server, "GET", "/_matrix/client/v1/auth_metadata").first)
    }

    @Test
    fun theRightPasswordGetsATokenWhoamiKnows() {
        val server = SignInMockServer()
        val (status, body) = signIn(server, name = "@Alice:iskra.test")
        assertEquals(200, status)
        assertEquals("@alice:iskra.test", body["user_id"])
        val whoami = call(server, "GET", "/_matrix/client/v3/account/whoami", token = body["access_token"].str()).second as JSON
        assertEquals("@alice:iskra.test", whoami["user_id"])
        assertEquals(body["device_id"], whoami["device_id"])
    }

    @Test
    fun theWrongPasswordIsRefused() {
        assertEquals(403, signIn(SignInMockServer(), password = "wrong").first)
    }

    @Test
    fun aSignedOutTokenIsUnknown() {
        val server = SignInMockServer()
        val token = signIn(server).second["access_token"].str()
        call(server, "POST", "/_matrix/client/v3/logout", token = token)
        assertEquals(401, call(server, "GET", "/_matrix/client/v3/account/whoami", token = token).first)
    }

    /** SDK перечитывает загруженные ключи: не вернуть их — шифрование аккаунта «не взялось». */
    @Test
    fun uploadedKeysComeBackFromAQuery() {
        val server = SignInMockServer()
        val token = signIn(server).second["access_token"].str()
        call(server, "POST", "/_matrix/client/v3/keys/upload", """{"device_keys":{"device_id":"DEV","keys":{}}}""", token)
        call(server, "POST", "/_matrix/client/v3/keys/device_signing/upload", """{"master_key":{"keys":{"ed25519:MK":"MK"}}}""", token)
        val answer = call(server, "POST", "/_matrix/client/v3/keys/query", """{"device_keys":{"@alice:iskra.test":[]}}""", token).second as JSON
        assertNotNull(answer["device_keys"].obj()?.get("@alice:iskra.test").obj()?.get("DEV"))
        assertNotNull(answer["master_keys"].obj()?.get("@alice:iskra.test"))
    }

    @Test
    fun accountDataReadsBackWhatWasWritten() {
        val server = SignInMockServer()
        val token = signIn(server).second["access_token"].str()
        val path = "/_matrix/client/v3/user/@alice:iskra.test/account_data/m.secret_storage.default_key"
        assertEquals(404, call(server, "GET", path, token = token).first)
        call(server, "PUT", path, """{"key":"K"}""", token)
        assertEquals("K", (call(server, "GET", path, token = token).second as JSON)["key"])
    }

    @Test
    fun theBrowserPageSendsTheBrowserHomeWithACode() {
        val server = SignInMockServer()
        val home = location(server, "/_mock/oauth/approve?flow=signIn&redirect_uri=wtf.iskra%3A%2Foauth-callback&state=S&username=alice&password=password")
        assertTrue(home.orEmpty(), home.orEmpty().startsWith("wtf.iskra:/oauth-callback?code=code_"))
        assertTrue(home.orEmpty().endsWith("&state=S"))
    }

    @Test
    fun registeringMakesTheAccountAndATakenNameStaysOnThePage() {
        val server = SignInMockServer()
        assertNotNull(location(server, "/_mock/oauth/approve?flow=register&redirect_uri=x%3A%2Fy&username=boris&password=secret"))
        assertEquals("secret", server.accounts["boris"])

        val (status, page) = call(server, "GET", "/_mock/oauth/approve?flow=register&redirect_uri=x%3A%2Fy&username=alice&password=x")
        assertEquals(200, status)
        assertTrue((page as String).contains("занято"))
        assertNull(location(server, "/_mock/oauth/approve?flow=register&redirect_uri=x%3A%2Fy&username=alice&password=x"))
    }

    /** Бэкап — свой у каждого: второй аккаунт не должен найти бэкап первого (`BackupExistsOnServer`). */
    @Test
    fun aBackupBelongsToItsUser() {
        val server = SignInMockServer(SignInMockServer.Configuration(accounts = mapOf("alice" to "password", "bob" to "password")))
        val alice = signIn(server).second["access_token"].str()
        val bob = signIn(server, name = "bob").second["access_token"].str()
        val path = "/_matrix/client/v3/room_keys/version"
        call(server, "POST", path, """{"algorithm":"m.megolm_backup.v1.curve25519-aes-sha2","auth_data":{}}""", alice)

        assertEquals("1", (call(server, "GET", path, token = alice).second as JSON)["version"])
        assertEquals(404, call(server, "GET", path, token = bob).first)
    }

    @Test
    fun anEmptyAccountSyncsWithEveryListEmpty() {
        val server = SignInMockServer()
        val answer = call(server, "POST", "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync", """{"lists":{"all":{}}}""").second as JSON
        assertEquals(0, answer["lists"].obj()?.get("all").obj()?.get("count").int())
        assertEquals("1", answer["pos"])
    }

    @Test
    fun theDeviceComesFromTheScope() {
        assertEquals("ABCDEF", SignInMockServer.deviceInScope("openid urn:matrix:org.matrix.msc2967.client:device:ABCDEF"))
        assertEquals("XYZ", SignInMockServer.deviceInScope("urn:matrix:client:device:XYZ urn:matrix:client:api:*"))
    }

    @Test
    fun anythingElseIsUnrecognisedAndWrittenDown() {
        val server = SignInMockServer()
        assertEquals(404, call(server, "GET", "/_matrix/client/v3/thirdparty/protocols").first)
        assertEquals(listOf("GET /_matrix/client/v3/thirdparty/protocols"), server.unanswered)
    }
}
