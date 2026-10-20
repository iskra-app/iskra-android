package wtf.iskra.core.mock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import wtf.iskra.core.security.InMemorySecrets
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.session.AuthenticationError
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.Strings
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Настоящий `AuthenticationService`, настоящий SDK и ненастоящий сервер. Порт
 * `SignInMockServerTests.swift` и аккаунтной части `MockSignInTests.swift`.
 *
 * Доказывают, что сервер входа годится вместо настоящего от первой буквы адреса до `UserSession`, —
 * ради этого он и есть. Начали падать после обновления SDK — SDK стал спрашивать то, чего сервер не
 * отвечает, и `server.unanswered` это называет.
 */
@RunWith(AndroidJUnit4::class)
class SignInMockServerSdkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "IskraMockTests-${UUID.randomUUID()}")
    private val servers = mutableListOf<SignInMockServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        scratch.deleteRecursively()
    }

    private fun server(configuration: SignInMockServer.Configuration = SignInMockServer.Configuration()) =
        SignInMockServer(configuration).also { servers += it }

    private fun service() = AuthenticationService(
        environment = AppEnvironment(File(scratch, "files"), File(scratch, "cache"), context.packageName),
        sessionStore = SessionStore(InMemorySecrets()),
        deviceName = "Iskra (tests)",
        userAgent = "Iskra/tests",
        strings = Strings.of(context),
    )

    @Test
    fun discoveryReportsEveryWayInTheServerOffers() = runBlocking {
        val address = server().start()
        val options = service().startSignIn(address)
        assertTrue(options.supportsPassword)
        assertTrue(options.supportsOAuth)
        assertTrue(options.supportsRegistration)
    }

    @Test
    fun aPasswordOnlyServerOffersNoRegistration() = runBlocking {
        val address = server(SignInMockServer.Configuration(offersOAuth = false, offersRegistration = false)).start()
        val options = service().startSignIn(address)
        assertTrue(options.supportsPassword)
        assertTrue(!options.canUseSingleSignOn)
        assertTrue(!options.supportsRegistration)
    }

    @Test
    fun theRightPasswordEndsInASignedInSession() = runBlocking {
        val server = server()
        val authentication = service()
        authentication.startSignIn(server.start())
        val session = authentication.signIn("alice", "password")
        assertEquals("@alice:iskra.test", session.userId)
        session.signOut()
    }

    @Test
    fun theWrongPasswordIsRefused() = runBlocking {
        val authentication = service()
        authentication.startSignIn(server().start())
        assertFailsWith<AuthenticationError> { authentication.signIn("alice", "wrong") }
        Unit
    }

    @Test
    fun registeringThroughTheBrowserMakesTheAccountAndSignsIntoIt() = runBlocking {
        val server = server()
        val authentication = service()
        authentication.startSignIn(server.start())
        val answer = Browser.submit(authentication.registrationUrl(), "boris", "secret")
        val callback = assertNotNull(answer.location, "страница должна была перенаправить: ${answer.body}")
        assertTrue(callback.startsWith("wtf.iskra:"))

        val session = authentication.completeSingleSignOn(callback)
        assertEquals("@boris:iskra.test", session.userId)
        assertEquals("secret", server.accounts["boris"])
        session.signOut()
    }

    @Test
    fun registeringATakenNameStaysOnThePage() = runBlocking {
        val authentication = service()
        authentication.startSignIn(server().start())
        val answer = Browser.submit(authentication.registrationUrl(), "alice", "anything")
        assertEquals(200, answer.status)
        assertEquals(null, answer.location)
        assertTrue(answer.body.contains("занято"))
    }

    @Test
    fun signingInThroughTheBrowserWorksAsWellAsByPassword() = runBlocking {
        val authentication = service()
        authentication.startSignIn(server().start())
        val answer = Browser.submit(authentication.singleSignOnUrl(), "alice", "password")
        val session = authentication.completeSingleSignOn(assertNotNull(answer.location))
        assertEquals("@alice:iskra.test", session.userId)
        session.signOut()
    }

    @Test
    fun legacySsoComesBackWithALoginToken() = runBlocking {
        val configuration = SignInMockServer.Configuration(offersPassword = false, offersLegacySso = true, offersOAuth = false, offersRegistration = false)
        val authentication = service()
        val options = authentication.startSignIn(server(configuration).start())
        assertTrue(options.supportsLegacySso)
        assertTrue(!options.supportsOAuth)

        val answer = Browser.submit(authentication.singleSignOnUrl(), "alice", "password")
        val session = authentication.completeSingleSignOn(assertNotNull(answer.location, answer.body))
        assertEquals("@alice:iskra.test", session.userId)
        session.signOut()
    }

    // MARK: Несколько аккаунтов

    private suspend fun signIn(authentication: AuthenticationService, address: String, name: String) =
        authentication.startSignIn(address).let { authentication.signIn(name, "password") }

    @Test
    fun aSecondAccountIsAddedBesideTheFirstWhichStaysSignedIn() = runBlocking {
        val address = server(SignInMockServer.Configuration(accounts = mapOf("alice" to "password", "bob" to "password"))).start()
        val authentication = service()
        val alice = signIn(authentication, address, "alice")
        val bob = signIn(authentication, address, "bob")

        assertEquals(listOf("@alice:iskra.test", "@bob:iskra.test"), authentication.accounts().map { it.userId })
        assertEquals(bob.sessionId, authentication.activeAccountId())
        assertNotEquals(alice.sessionId, bob.sessionId)

        // Выход из одного оставляет другой где был.
        bob.signOut()
        assertEquals(listOf("@alice:iskra.test"), authentication.accounts().map { it.userId })
        assertEquals(alice.sessionId, authentication.activeAccountId())
        alice.signOut()
    }

    @Test
    fun addingTheAccountThatIsOpenIsRefused() = runBlocking {
        val address = server().start()
        val authentication = service()
        val alice = signIn(authentication, address, "alice")
        assertFailsWith<AuthenticationError.AlreadySignedIn> { signIn(authentication, address, "alice") }
        assertEquals(1, authentication.accounts().size)
        alice.signOut()
    }

    /** Аккаунт, который не открылся, чинится повторным входом: старая запись уступает место. */
    @Test
    fun signingInAgainToAClosedAccountReplacesItsRecord() = runBlocking {
        val address = server().start()
        val authentication = service()
        val first = signIn(authentication, address, "alice")
        first.close()
        val second = signIn(authentication, address, "alice")

        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(listOf(second.sessionId), authentication.accounts().map { it.sessionId })
        second.signOut()
    }

    /** Выход из закрытого аккаунта: открывается рядом без синхронизации, выходит, открытый не трогается. */
    @Test
    fun signingOutOfAClosedAccountLeavesTheOpenOneAlone() = runBlocking {
        val address = server(SignInMockServer.Configuration(accounts = mapOf("alice" to "password", "bob" to "password"))).start()
        val authentication = service()
        val alice = signIn(authentication, address, "alice")
        alice.close()
        val bob = signIn(authentication, address, "bob")

        authentication.signOutInBackground(alice.sessionId)

        assertEquals(listOf(bob.sessionId), authentication.accounts().map { it.sessionId })
        assertEquals(bob.sessionId, authentication.activeAccountId())
        bob.signOut()
    }

    // MARK: Один клиент на стор (пуш и приложение в одном процессе)

    /** Пуш при открытом сеансе берёт его клиент, а не собирает второй поверх того же SQLite. */
    @Test
    fun aPushBorrowsTheOpenSessionsClient() = runBlocking {
        val authentication = service()
        val alice = signIn(authentication, server().start(), "alice")
        val leased = assertNotNull(authentication.leaseClient(alice.sessionId))
        assertTrue(leased === alice.client)
        authentication.releaseClient(alice.sessionId, leased)
        // Отдал — сеанс жив: клиент закрывает последний держатель, а это сеанс.
        assertEquals("@alice:iskra.test", alice.client.userId())
        alice.signOut()
    }

    /**
     * Своё, не из iOS. Пуш открыл закрытый аккаунт, и тут человек открыл приложение: восстановление
     * получает клиент пуша. И пуш, закончив, не закрывает его из-под сеанса.
     */
    @Test
    fun theAppOpeningDuringAPushGetsThePushsClient() = runBlocking {
        val authentication = service()
        val first = signIn(authentication, server().start(), "alice")
        val id = first.sessionId
        first.close()

        val pushClient = assertNotNull(authentication.leaseClient(id))
        val session = assertNotNull(authentication.restoreSession(id))
        assertTrue(session.client === pushClient)

        authentication.releaseClient(id, pushClient)
        assertEquals("@alice:iskra.test", session.client.userId())
        session.signOut()
    }

    /** Играет браузер: открывает страницу, заполняет её форму, как человек, и не идёт по перенаправлению. */
    private object Browser {
        data class Answer(val status: Int, val location: String?, val body: String)

        fun submit(page: String, username: String, password: String): Answer {
            val html = (URL(page).openConnection() as HttpURLConnection).run { inputStream.use { it.readBytes().decodeToString() } }
            val fields = Regex("""<input type="hidden" name="([^"]*)" value="([^"]*)">""").findAll(html)
                .associate { unescape(it.groupValues[1]) to unescape(it.groupValues[2]) } + mapOf("username" to username, "password" to password)
            val query = fields.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
            val origin = page.substringBefore("/_m")
            val connection = URL("$origin/_mock/oauth/approve?$query").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            val status = connection.responseCode
            val body = runCatching { connection.inputStream.use { it.readBytes().decodeToString() } }.getOrDefault("")
            return Answer(status, connection.getHeaderField("Location"), body)
        }

        private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

        private fun unescape(text: String) =
            text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
