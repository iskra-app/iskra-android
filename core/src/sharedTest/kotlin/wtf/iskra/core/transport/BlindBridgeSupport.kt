package wtf.iskra.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import wtf.iskra.core.transport.nostr.Relays
import wtf.iskra.core.transport.nostr.StreamCarrier
import wtf.iskra.core.transport.nostr.StreamPump
import wtf.iskra.tunnel.FrameOp
import wtf.iskra.tunnel.FrameSealer
import wtf.iskra.tunnel.frameOpened
import wtf.iskra.tunnel.frameRst
import wtf.iskra.tunnel.generateSecretKey
import wtf.iskra.tunnel.publicKeyHex
import java.net.InetAddress
import kotlin.random.Random

/**
 * Relay в памяти: событие, опубликованное одним, доставляется всем, чей фильтр его берёт, — как на
 * настоящем relay'е, только без сети. По желанию перемешивает (каждое событие со своей случайной
 * задержкой) и дублирует (каждое ещё раз, позже), как это делают несколько настоящих relay'ев разом:
 * насос обязан это переживать, а не надеяться, что не случится.
 */
class MemoryRelay(
    private val shuffle: Boolean = false,
    private val duplicate: Boolean = false,
    /** Отвечает ли relay вообще: «ни один relay не подключён» — это `false`. */
    private val up: Boolean = true,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val subscribers = java.util.concurrent.CopyOnWriteArrayList<Pair<JsonObject, (String) -> Unit>>()

    /** Сколько событий прошло — чтобы тест видел, что трафик был. */
    @Volatile
    var published = 0
        private set

    fun relays(filter: String, onEvent: (String) -> Unit): Relays = object : Relays {
        override val connected: Int get() = if (up) 2 else 0

        override fun start() {
            subscribers += Json.parseToJsonElement(filter).jsonObject to onEvent
        }

        override fun stop() {
            subscribers.removeIf { it.second === onEvent }
        }

        override suspend fun publish(eventJson: String) {
            if (!up) throw wtf.iskra.core.transport.nostr.NoRelayConnected()
            this@MemoryRelay.publish(eventJson)
        }

        override suspend fun wake() = Unit

        override fun sever() = Unit
    }

    fun publish(eventJson: String) {
        published += 1
        val event = Json.parseToJsonElement(eventJson).jsonObject
        for ((filter, deliver) in subscribers) {
            if (!matches(filter, event)) continue
            if (!shuffle && !duplicate) {
                deliver(eventJson)
                continue
            }
            scope.launch {
                if (shuffle) delay(Random.nextLong(0, 15))
                deliver(eventJson)
            }
            if (duplicate) {
                scope.launch {
                    delay(Random.nextLong(5, 40))
                    deliver(eventJson)
                }
            }
        }
    }

    fun close() = scope.cancel()

    private fun matches(filter: JsonObject, event: JsonObject): Boolean {
        val kind = event["kind"]!!.jsonPrimitive.long
        val author = event["pubkey"]!!.jsonPrimitive.content
        val p = event["tags"]!!.jsonArray.map { it.jsonArray }.filter { it[0].jsonPrimitive.content == "p" }.map { it[1].jsonPrimitive.content }
        fun list(name: String) = (filter[name] as? JsonArray)?.map { (it as JsonPrimitive).content }
        return list("kinds")?.contains(kind.toString()) != false &&
            list("authors")?.contains(author) != false &&
            list("#p")?.any { it in p } != false
    }
}

/**
 * Мост-заглушка: тот же насос, что у клиента, с ролью [StreamPump.Role.Bridge], на кадрах из
 * [MemoryRelay]. Соединяется с тем, куда просит `open`, — с TLS-сервером теста — и двигает шифротекст,
 * не заглядывая внутрь, как настоящий. [refuse] — слово, которым он отвергает каждый `open`; [silent] —
 * мост, который не отвечает ничего (не тот ключ, не запущен).
 */
class StandInBridge(
    private val relay: MemoryRelay,
    clientPublicKeyHex: String,
    private val refuse: String? = null,
    private val silent: Boolean = false,
) {
    val secretKey: ByteArray = generateSecretKey()
    val publicKeyHex: String = publicKeyHex(secretKey)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sealer = FrameSealer.forBridge(secretKey, clientPublicKeyHex)
    private val carrier = StreamCarrier(link = { frame -> relay.publish(sealer.seal(frame, 0u)) }, background = scope)
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val relays = relay.relays(sealer.subscriptionFilter(0u)) { incoming.trySend(it) }

    /** Сколько потоков мост соединил. */
    @Volatile
    var opened = 0
        private set

    /** Локальные порты соединений, которые мост открыл к серверу: по ним видно, что пришло через него. */
    val ports: MutableList<Int> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        relays.start()
        scope.launch {
            for (json in incoming) {
                if (silent) continue
                val frame = sealer.open(json) ?: continue
                if (frame.op == FrameOp.OPEN && !carrier.has(frame.sid)) {
                    if (refuse != null) {
                        relay.publish(sealer.seal(frameRst(frame.sid, refuse), 0u))
                        continue
                    }
                    val socket = LocalSocket.connect(frame.host!!, frame.port!!.toInt())
                    if (socket == null) {
                        relay.publish(sealer.seal(frameRst(frame.sid, "unreachable"), 0u))
                        continue
                    }
                    ports += socket.localPort
                    val pump = StreamPump(frame.sid, StreamPump.Role.Bridge, socket, ByteArray(0), { relay.publish(sealer.seal(it, 0u)) }, scope)
                    carrier.register(pump)
                    opened += 1
                    scope.launch { carrier.run(pump) }
                    relay.publish(sealer.seal(frameOpened(frame.sid), 0u))
                }
                carrier.deliver(frame)
            }
        }
    }

    fun close() {
        relays.stop()
        scope.cancel()
        sealer.close()
    }
}

/** TLS-сервер на loopback с сертификатом, которому доверяет только клиент теста, — сервер Matrix в миниатюре. */
class TlsServer : AutoCloseable {
    private val localhost = InetAddress.getByName("localhost").canonicalHostName
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val certificate = HeldCertificate.Builder()
        .signedBy(root)
        .addSubjectAlternativeName(localhost)
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()

    val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    val url: String get() = "https://localhost:${server.port}"

    /** Клиент, который доверяет этому серверу и больше никому. */
    val trustingClient: OkHttpClient = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().let { trust ->
        OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
    }

    override fun close() = server.shutdown()
}
