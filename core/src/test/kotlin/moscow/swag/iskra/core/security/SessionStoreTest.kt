package moscow.swag.iskra.core.security

import moscow.swag.iskra.core.support.AppEnvironment
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Порт решений `SessionPersistenceTests`: секреты по аккаунтам, и выход одного не трогает другого. */
class SessionStoreTest {
    private val secrets = InMemorySecrets()
    private val store = SessionStore(secrets)
    private val root = Files.createTempDirectory("iskra").toFile()
    private val environment = AppEnvironment(root.resolve("files"), root.resolve("cache"), "test")

    private fun session(id: String, user: String) = StoredSession(id, user, "DEV$id", "https://matrix.org", "token-$id", "refresh-$id", null)

    @Test
    fun registerPutsTheNewAccountOnScreen() {
        store.register(session("a", "@ann:matrix.org"))
        store.register(session("b", "@bob:matrix.org"))
        assertEquals(listOf("@ann:matrix.org", "@bob:matrix.org"), store.accounts().map { it.userId })
        assertEquals("b", store.activeSessionId())
        assertEquals("token-b", store.loadSession()?.accessToken)
        store.setActive("a")
        assertEquals("token-a", store.loadSession()?.accessToken)
    }

    /** Выход первого аккаунта не забирает ключ стора второго — главное, ради чего всё по аккаунтам. */
    @Test
    fun removingOneAccountKeepsTheOthersSecrets() {
        store.register(session("a", "@ann:matrix.org"))
        store.register(session("b", "@bob:matrix.org"))
        val bobPassphrase = store.storePassphrase("b")
        val bobSearch = store.searchIndexPassword("b")
        store.storePassphrase("a")
        environment.sessionDirs("a").forEach { it.mkdirs() }
        environment.sessionDirs("b").forEach { it.mkdirs() }

        store.remove("a", environment)

        assertEquals(listOf("b"), store.accounts().map { it.sessionId })
        assertNull(store.loadSession("a"))
        assertEquals(bobPassphrase, store.storePassphrase("b"))
        assertEquals(bobSearch, store.searchIndexPassword("b"))
        assertTrue(environment.sessionDirs("b").all { it.isDirectory })
        assertTrue(environment.sessionDirs("a").none { it.exists() })
    }

    @Test
    fun removingTheActiveAccountPutsTheNextOneOnScreen() {
        store.register(session("a", "@ann:matrix.org"))
        store.register(session("b", "@bob:matrix.org"))
        store.remove("b", environment)
        assertEquals("a", store.activeSessionId())
        store.remove("a", environment)
        assertNull(store.activeSessionId())
        assertNull(store.loadSession())
    }

    @Test
    fun passphrasesAreStableRandomAndPerAccount() {
        val first = store.storePassphrase("a")
        assertEquals(first, store.storePassphrase("a"))
        assertNotEquals(first, store.storePassphrase("b"))
        assertNotEquals(first, store.searchIndexPassword("a"))
        assertEquals(32, java.util.Base64.getDecoder().decode(first).size)
    }

    /** Обновление токена пишет только токены: индекс трогает только вход. */
    @Test
    fun savingTokensDoesNotTouchTheIndex() {
        store.save(session("a", "@ann:matrix.org"))
        assertTrue(store.accounts().isEmpty())
        assertEquals("token-a", store.loadSession("a")?.accessToken)
    }

    @Test
    fun profileIsRememberedForClosedAccounts() {
        store.register(session("a", "@ann:matrix.org"))
        store.updateProfile("a", "Аня", "mxc://matrix.org/abc")
        assertEquals("Аня", store.accounts().single().displayName)
        store.register(session("a", "@ann:matrix.org"))
        assertEquals("Аня", store.accounts().single().displayName, "повторный вход не стирает кэш профиля")
    }

    @Test
    fun secretsNeverReachToString() {
        val text = session("a", "@ann:matrix.org").toString()
        assertFalse("token-a" in text)
        assertFalse("refresh-a" in text)
    }

    @Test
    fun serverNameComesFromTheUserId() {
        assertEquals("matrix.org", session("a", "@ann:matrix.org").serverName)
        assertNull(session("a", "ann").serverName)
    }
}
