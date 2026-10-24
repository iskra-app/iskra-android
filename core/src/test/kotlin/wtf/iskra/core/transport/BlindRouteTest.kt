package wtf.iskra.core.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import wtf.iskra.core.transport.nostr.BridgeRefusal
import wtf.iskra.core.transport.nostr.NostrStreamTunnel
import wtf.iskra.tunnel.generateSecretKey
import wtf.iskra.tunnel.publicKeyHex
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Слепой маршрут целиком, без сети: OkHttp → [LocalConnectProxy] → [NostrStreamTunnel] → relay в
 * памяти, который перемешивает и дублирует, → мост-заглушка на том же насосе → TLS-сервер. Порт
 * сути `BlindRouteTests` (docs/PROMPT.md, 9.3): кадры, окна и запечатывание — настоящий Rust из `:tunnel`,
 * TLS — сквозной, а мост видит только шифротекст.
 *
 * SDK здесь нет — он только для Android; сквозной вход и sync через мост — инструментальный тест.
 */
class BlindRouteTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun tearDown() = closers.reversed().forEach { runCatching { it() } }

    private class Route(val proxy: LocalConnectProxy, val tunnel: NostrStreamTunnel, val bridge: StandInBridge, val relay: MemoryRelay)

    private suspend fun route(
        shuffle: Boolean = true,
        duplicate: Boolean = true,
        refuse: String? = null,
        silent: Boolean = false,
        relayUp: Boolean = true,
    ): Route {
        val relay = MemoryRelay(shuffle, duplicate, up = relayUp)
        val device = generateSecretKey()
        val bridge = StandInBridge(relay, publicKeyHex(device), refuse = refuse, silent = silent)
        val tunnel = NostrStreamTunnel(bridge.publicKeyHex, device, { filter, onEvent -> relay.relays(filter, onEvent) })
        tunnel.start()
        val proxy = LocalConnectProxy(tunnel)
        proxy.start()
        closers += { relay.close() }
        closers += { bridge.close() }
        closers += { tunnel.stop() }
        closers += { runBlocking { proxy.stop() } }
        return Route(proxy, tunnel, bridge, relay)
    }

    private fun server(): TlsServer = TlsServer().also { closers += { it.close() } }

    @Test
    fun aRequestGoesThroughAndComesBackWhole() = runBlocking<Unit> {
        val server = server()
        server.server.enqueue(MockResponse().setBody("""{"versions":["v1.11"]}"""))
        val route = route()
        val client = route.proxy.httpClient(server.trustingClient)
        val body = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("${server.url}/_matrix/client/versions").build()).execute().use { it.body!!.string() }
        }
        assertEquals("""{"versions":["v1.11"]}""", body)
        assertTrue(route.bridge.opened >= 1, "the bridge connected the stream")
        assertTrue(route.tunnel.heard.everOpened)
    }

    /**
     * Ответ во много окон: сотни сегментов, которые приходят не по порядку и по два раза, и
     * подтверждения, без которых отправитель встал бы на первом же мегабайте.
     */
    @Test
    fun aLargeBodyCrossesManyWindowsInOrder() = runBlocking<Unit> {
        val server = server()
        val payload = Random(7).nextBytes(3 * 1024 * 1024)
        server.server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val route = route()
        val client = route.proxy.httpClient(server.trustingClient)
        val received = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url("${server.url}/_matrix/media/v3/download/x").build()).execute().use { it.body!!.bytes() }
        }
        assertContentEquals(sha(payload), sha(received))
    }

    /** И в другую сторону: загрузка, где окно держит уже клиент. */
    @Test
    fun anUploadCrossesTooAndTheServerGetsEveryByte() = runBlocking<Unit> {
        val server = server()
        val payload = Random(11).nextBytes(2 * 1024 * 1024)
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(hex(sha(request.body.readByteArray())))
        }
        val route = route()
        val client = route.proxy.httpClient(server.trustingClient)
        val answer = withContext(Dispatchers.IO) {
            val body = okhttp3.RequestBody.Companion.run { payload.toRequestBody(null) }
            client.newCall(Request.Builder().url("${server.url}/_matrix/media/v3/upload").post(body).build()).execute().use { it.body!!.string() }
        }
        assertEquals(hex(sha(payload)), answer)
    }

    /** Несколько потоков разом через один диспетчер: каждый получает своё, а не соседское. */
    @Test
    fun parallelStreamsDoNotCross() = runBlocking<Unit> {
        val server = server()
        server.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody(request.path!!.substringAfterLast('/').repeat(5000))
        }
        val route = route()
        val client = route.proxy.httpClient(server.trustingClient)
        val answers = (1..6).map { n ->
            async(Dispatchers.IO) {
                // Своё соединение на каждый: иначе OkHttp пустил бы их одним HTTP/2.
                val fresh = client.newBuilder().connectionPool(okhttp3.ConnectionPool()).build()
                fresh.newCall(Request.Builder().url("${server.url}/room/$n").build()).execute().use { it.body!!.string() }
            }
        }.awaitAll()
        answers.forEachIndexed { index, body -> assertEquals("${index + 1}".repeat(5000), body) }
    }

    // ---- Проверка моста: пять отказов, которые изнутри выглядят одинаково.

    @Test
    fun theCheckSaysReachable() = runBlocking<Unit> {
        val server = server()
        server.server.enqueue(MockResponse().setBody("{}"))
        val route = route()
        val outcome = BridgeCheck.check(route.proxy, server.url, 20.seconds, server.trustingClient, route.tunnel.heard) { route.tunnel.connectedRelays }
        assertIs<BridgeCheck.Outcome.Reachable>(outcome)
    }

    @Test
    fun theCheckNamesTheBridgesRefusal() = runBlocking<Unit> {
        val server = server()
        val route = route(refuse = "not allowed")
        val outcome = BridgeCheck.check(route.proxy, server.url, 20.seconds, server.trustingClient, route.tunnel.heard) { route.tunnel.connectedRelays }
        assertEquals(BridgeCheck.Outcome.Refused(BridgeRefusal.NotAllowed), outcome)
        assertEquals(TunnelStatus.Refused(BridgeRefusal.NotAllowed), TunnelStatus.of(route.tunnel.heard, 2))
    }

    @Test
    fun aBridgeThatSaysNothingIsSilent() = runBlocking<Unit> {
        val server = server()
        val route = route(silent = true)
        val outcome = BridgeCheck.check(route.proxy, server.url, 3.seconds, server.trustingClient, route.tunnel.heard) { route.tunnel.connectedRelays }
        assertEquals(BridgeCheck.Outcome.Silent, outcome)
        assertEquals(TunnelStatus.Connecting(2), TunnelStatus.of(route.tunnel.heard, 2))
    }

    @Test
    fun noRelayIsNoRelay() = runBlocking<Unit> {
        val server = server()
        val route = route(relayUp = false)
        val outcome = BridgeCheck.check(route.proxy, server.url, 3.seconds, server.trustingClient, route.tunnel.heard) { route.tunnel.connectedRelays }
        assertEquals(BridgeCheck.Outcome.NoRelay, outcome)
    }

    /** Листенер, которого лишились, поднимается на том же порту: SDK другого не знает. */
    @Test
    fun aLostListenerComesBackOnTheSamePort() = runBlocking<Unit> {
        val server = server()
        server.server.enqueue(MockResponse().setBody("one"))
        server.server.enqueue(MockResponse().setBody("two"))
        val route = route(shuffle = false, duplicate = false)
        val port = route.proxy.port
        val client = route.proxy.httpClient(server.trustingClient)
        fun get() = client.newBuilder().connectionPool(okhttp3.ConnectionPool()).build()
            .newCall(Request.Builder().url("${server.url}/x").build()).execute().use { it.body!!.string() }
        assertEquals("one", withContext(Dispatchers.IO) { get() })
        route.proxy.simulateLoss()
        route.proxy.ensureListening()
        assertEquals(port, route.proxy.port)
        assertEquals("two", withContext(Dispatchers.IO) { get() })
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
