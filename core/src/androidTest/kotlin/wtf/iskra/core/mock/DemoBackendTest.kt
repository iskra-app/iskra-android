package wtf.iskra.core.mock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.runner.RunWith
import wtf.iskra.core.rooms.RoomKind
import wtf.iskra.core.rooms.RoomListState
import wtf.iskra.core.security.InMemorySecrets
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.Strings
import wtf.iskra.core.timeline.MessageKind
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Демо-сервер (`MockHomeserver`, `MockWorld`) против **настоящего SDK**: вход, sliding sync до списка
 * чатов и лента комнаты — тот же путь, что проходит экран (docs/PROMPT.md, 14). Порт `DemoBackendTests`.
 *
 * Начал падать после обновления SDK — SDK стал спрашивать то, чего сервер не отвечает; что именно —
 * пишет `DemoBackend.unrecognized`, и тест печатает это в сообщении.
 */
@RunWith(AndroidJUnit4::class)
class DemoBackendTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "IskraDemoTests-${UUID.randomUUID()}")
    private var session: UserSession? = null

    @After
    fun tearDown() = runBlocking<Unit> {
        session?.let { withContext(Dispatchers.Main) { it.signOut() } }
        scratch.deleteRecursively()
    }

    private fun service() = AuthenticationService(
        environment = AppEnvironment(File(scratch, "files"), File(scratch, "cache"), context.packageName),
        sessionStore = SessionStore(InMemorySecrets()),
        deviceName = "Iskra (tests)",
        userAgent = "Iskra/tests",
        strings = Strings.of(context),
    )

    private fun unanswered() = "сервер не ответил на: ${DemoBackend.unrecognized}"

    @Test
    fun theDemoWorldArrivesThroughTheRealSdk() = runBlocking<Unit> {
        val demo = service().startDemoSession().also { session = it }
        assertTrue(demo.isDemo)
        assertEquals("@${DemoBackend.USERNAME}:iskra.demo", demo.userId)

        // Список — тем же явным вызовом, что делает корень, а не побочным эффектом экрана.
        withContext(Dispatchers.Main) { demo.rooms.start() }
        // Счётчики SDK досчитывает по квитанциям следующими пачками, а не в первой: ждём, пока сойдётся
        // всё, что видно на экране демо, а не первого появления строк.
        fun RoomListState.looksLikeTheDemo(): Boolean {
            val anya = rooms.firstOrNull { it.name == "Аня Смирнова" } ?: return false
            val family = rooms.firstOrNull { it.name == "Семья 🏡" } ?: return false
            val invitation = rooms.firstOrNull { it.name == "Книжный клуб 📚" } ?: return false
            return anya.kind == RoomKind.DIRECT && anya.hasUnread && family.isMentioned && invitation.isInvitation &&
                rooms.any { it.isSpace }
        }
        val reached = withTimeoutOrNull(LIMIT) { demo.rooms.state.first { it.looksLikeTheDemo() } }
        if (reached == null) {
            val last = demo.rooms.state.value.rooms.joinToString { "${it.name}: ${it.kind} unread=${it.unreadCount} @=${it.mentionCount} invite=${it.isInvitation}" }
            fail("список демо не сошёлся за $LIMIT — последнее: $last; ${unanswered()}")
        }
        val anya = reached.rooms.first { it.name == "Аня Смирнова" }

        // И переписка комнаты: явная первая страница, последнее сообщение Ани — словами.
        val timeline = assertNotNull(withContext(Dispatchers.Main) { demo.timeline(anya.id) })
        try {
            withContext(Dispatchers.Main) { timeline.start() }
            val rows = withTimeout(LIMIT) {
                timeline.state.first { it.hasLoaded && it.rows.isNotEmpty() }.rows
            }
            val words = rows.mapNotNull { (it.message?.kind as? MessageKind.Text)?.body }
            assertTrue("И захвати, пожалуйста, зарядку 🙌" in words, "слова Ани в ленте: $words; ${unanswered()}")
        } finally {
            withContext(Dispatchers.Main) { timeline.close() }
        }
    }

    private companion object {
        val LIMIT = 30.seconds
    }
}
