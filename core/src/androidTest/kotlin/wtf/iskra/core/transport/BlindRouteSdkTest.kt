package wtf.iskra.core.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.runner.RunWith
import wtf.iskra.core.mock.SignInMockServer
import wtf.iskra.core.security.InMemorySecrets
import wtf.iskra.core.security.SessionStore
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.support.AppEnvironment
import wtf.iskra.core.support.Strings
import wtf.iskra.core.transport.nostr.NostrStreamTunnel
import wtf.iskra.tunnel.generateSecretKey
import wtf.iskra.tunnel.publicKeyHex
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Порт `BlindRouteTests`: **настоящий SDK** входит и синхронизируется через слепой мост — прокси
 * `CONNECT`, туннель на Rust из `:tunnel`, relay в памяти, который перемешивает и дублирует кадры, и
 * мост-заглушку на том же насосе — к серверу за настоящим TLS, сертификату которого не доверяет никто,
 * кроме этого клиента (docs/PROMPT.md, 9.3).
 *
 * Доказывает две вещи разом. Что SDK, собранный с прокси, шлёт через него *всё* — поиск сервера, вход,
 * sliding sync: каждое соединение, принятое TLS-фронтом, пришло с порта, который открыл мост. И что
 * поток переживает relay'и как они есть — не по порядку и по два раза.
 */
@RunWith(AndroidJUnit4::class)
class BlindRouteSdkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch = File(context.cacheDir, "IskraBlindRoute-${UUID.randomUUID()}")
    private val closers = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it() } }
        scratch.deleteRecursively()
    }

    @Test
    fun signInAndSyncGoThroughTheBridgeAndNowhereElse() = runBlocking<Unit> {
        val server = SignInMockServer().also { closers += it::stop }
        val plain = server.start()
        val front = TlsFront(plain.substringAfterLast(':').toInt()).also { closers += it::close }
        server.advertiseAs(front.url)

        val relay = MemoryRelay(shuffle = true, duplicate = true).also { closers += it::close }
        val device = generateSecretKey()
        val bridge = StandInBridge(relay, publicKeyHex(device)).also { closers += it::close }
        val tunnel = NostrStreamTunnel(bridge.publicKeyHex, device, { filter, onEvent -> relay.relays(filter, onEvent) })
        tunnel.start()
        closers += tunnel::stop
        val proxy = LocalConnectProxy(tunnel)
        proxy.start()
        closers += { runBlocking { proxy.stop() } }

        val authentication = AuthenticationService(
            environment = AppEnvironment(File(scratch, "files"), File(scratch, "cache"), context.packageName),
            sessionStore = SessionStore(InMemorySecrets()),
            deviceName = "Iskra (tests)",
            userAgent = "Iskra/tests",
            strings = Strings.of(context),
        )
        authentication.fixedRoute = AuthenticationService.FixedRoute(proxy.proxyUrl!!, listOf(front.rootCertificate), tunnel.heard)

        authentication.startSignIn(front.url)
        val session = authentication.signIn("alice", "password")
        assertEquals("@alice:iskra.test", session.userId)

        session.rooms.start()
        val deadline = System.currentTimeMillis() + 60_000
        while (server.requests.none { "sync" in it } && System.currentTimeMillis() < deadline) delay(200)
        assertTrue(server.requests.any { "sync" in it }, "sliding sync never reached the server: ${server.requests}")

        assertTrue(front.remotePorts.isNotEmpty())
        val strays = front.remotePorts.filter { it !in bridge.ports }
        assertTrue(strays.isEmpty(), "connections reached the server around the bridge: $strays (bridge: ${bridge.ports})")
        assertTrue(tunnel.heard.everOpened)
        assertEquals(TunnelStatus.BridgedBlind(2), TunnelStatus.of(tunnel.heard, tunnel.connectedRelays))

        session.signOut()
    }
}

/**
 * TLS-фронт сервера входа: настоящий TLS на loopback с сертификатом на `localhost`, за ним — обычный
 * HTTP сервера. [remotePorts] — порты, с которых к нему пришли: по ним видно, кто их открыл.
 */
private class TlsFront(targetPort: Int) {
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val certificate = HeldCertificate.Builder()
        .signedBy(root)
        .addSubjectAlternativeName("localhost")
        .build()
    private val server: ServerSocket = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        .sslContext().serverSocketFactory
        .createServerSocket(0, 64, InetAddress.getByName("127.0.0.1")) as SSLServerSocket

    val remotePorts: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    /** Корень, которому доверяет SDK этого теста, — DER, как его берёт `ClientBuilder.addRootCertificates`. */
    val rootCertificate: ByteArray = root.certificate.encoded

    val url = "https://localhost:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                remotePorts += client.port
                thread(isDaemon = true) {
                    val upstream = runCatching { Socket("127.0.0.1", targetPort) }.getOrNull() ?: return@thread client.close()
                    thread(isDaemon = true) { pipe(client.getInputStream(), upstream.getOutputStream(), upstream) }
                    pipe(upstream.getInputStream(), client.getOutputStream(), client)
                }
            }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream, sink: Socket) {
        val buffer = ByteArray(16 * 1024)
        try {
            while (true) {
                val count = from.read(buffer)
                if (count < 0) break
                to.write(buffer, 0, count)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { sink.close() }
        }
    }

    fun close() = server.close()
}
