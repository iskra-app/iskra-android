package wtf.iskra.core.transport.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import wtf.iskra.core.support.Log
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Что туннелю нужно от relay'ев: [NostrRelayPool] в приложении, relay в памяти в тестах. */
interface Relays {
    /** Relay'и, у которых сокет открыт сейчас. */
    val connected: Int

    fun start()

    fun stop()

    /** Отдать событие во все открытые сокеты и вернуться. Бросает [NoRelayConnected], если некуда. */
    suspend fun publish(eventJson: String)

    /** Проверить соединения сейчас и переподключить то, чего на самом деле нет. */
    suspend fun wake()

    /** Оборвать все соединения разом, как это делает долгий сон. Для тестов. */
    fun sever()
}

class NoRelayConnected : Exception("ни один relay не подключён")

/**
 * Несколько relay'ев как один. Порт `NostrRelayPool.swift`.
 *
 * Список relay'ев — не запаска, а то, как это работает: событие публикуется на все сразу, и ответ
 * приходит с того, кто быстрее, поэтому relay, который лежит, — обычный вторник, а не авария, и
 * ничего не стоит (docs/PROMPT.md, 9.5). Обратная сторона — дубликаты: каждый relay доставляет свою
 * копию, и они отбрасываются здесь по id, так что выше их не видит никто.
 *
 * **Ничто здесь не ждёт `OK`.** [publish] отдаёт событие во все открытые сокеты и возвращается; ждать
 * нужно *ответа* моста. Ожидание подтверждений превратило на мосту трёхчанковый ответ sync в двадцать
 * секунд. Поэтому здесь свой пул на OkHttp, а не `nostr-sdk`: его `send_event` ждёт всех.
 *
 * Один сокет на relay и один читатель на сокет на всю жизнь пула, не на запрос: подписка на запрос
 * оставляла N-му вызову N живых подписок на каждом relay'е, и каждая доставляла свою копию ответа.
 */
class NostrRelayPool(
    private val relays: List<String>,
    /** Фильтр подписки, JSON — `FrameSealer.subscriptionFilter`. */
    private val filterJson: String,
    /** Id события, если оно подлинное (`verifyEvent`); null — подделка. */
    private val verify: (String) -> String?,
    private val onEvent: (String) -> Unit,
    private val client: OkHttpClient = defaultClient,
) : Relays {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subscriptionId = "iskra-" + UUID.randomUUID().toString().take(8)

    /** Открытые сокеты по relay'ю. Переподключающегося relay'я здесь просто нет. */
    private val sockets = ConcurrentHashMap<String, Relay>()
    private val readers = mutableListOf<Job>()

    @Volatile
    private var running = false

    /** Растёт с каждым [wake]: петля переподключения, спящая в backoff, видит это и перестаёт ждать. */
    private val wakes = MutableStateFlow(0)

    /**
     * Id уже отданных наверх событий, новые в конце, — чтобы узнать дубликат со второго relay'я.
     * С потолком: пул живёт весь сеанс, и множество без границы было бы утечкой без симптомов.
     */
    private val seen = LinkedHashSet<String>()

    override val connected: Int get() = sockets.size

    @Synchronized
    override fun start() {
        if (running) return
        running = true
        for (relay in relays) readers += scope.launch { run(relay) }
    }

    @Synchronized
    override fun stop() {
        running = false
        readers.forEach { it.cancel() }
        readers.clear()
        sockets.values.forEach { it.cancel() }
        sockets.clear()
        scope.cancel()
    }

    /**
     * Опубликовать на всех relay'ях, что подключены.
     *
     * Всем, а не одному: relay может принять соединение и молча выбросить данное, и отсюда этого не
     * узнать. Кто донёс первым — тот и выиграл. Холодный старт ждёт до [waitMillis]: первый запрос
     * обычно приходит, пока сокеты ещё здороваются, и проваливать его за это — делать туннель
     * сломанным на вид при каждом открытии приложения.
     */
    override suspend fun publish(eventJson: String) = publish(eventJson, waitMillis = 10_000)

    suspend fun publish(eventJson: String, waitMillis: Long) {
        if (sockets.isEmpty()) {
            val deadline = System.nanoTime() + waitMillis * 1_000_000
            while (sockets.isEmpty() && running && System.nanoTime() < deadline) delay(100)
        }
        if (sockets.isEmpty()) throw NoRelayConnected()
        val message = """["EVENT",$eventJson]"""
        for ((url, relay) in sockets) {
            if (!relay.socket.send(message)) drop(relay, url)
        }
    }

    /**
     * Проверить каждое соединение сейчас и переподключить то, чего на самом деле нет.
     *
     * Зовётся по дороге с фона. Телефон в кармане держит сокеты только на бумаге: процесс спит,
     * relay'и вешают трубку на соединение, которое перестало отвечать, и после пробуждения каждый
     * сокет ещё *выглядит* открытым — опубликованное в него уходит в никуда, и запрос ждёт свой лимит,
     * прежде чем кто-нибудь узнает. А петли переподключения спят в backoff, который к тому времени мог
     * дорасти до тридцати секунд. Поэтому: короткий вопрос каждому сокету, кто не ответил за три
     * секунды — сброшен, и все петли, что ждут переподключения, перестают ждать.
     *
     * Вопрос — пустая подписка (`limit: 0`), на которую relay отвечает `EOSE`: ping у OkHttp наружу
     * не выведен, а любое слово relay'я значит, что он там.
     */
    override suspend fun wake() {
        if (!running) return
        wakes.value += 1
        val current = sockets.toMap()
        current.map { (url, relay) ->
            scope.async {
                if (!relay.answers(within = 3_000)) drop(relay, url)
            }
        }.awaitAll()
    }

    override fun sever() {
        for ((url, relay) in sockets) drop(relay, url)
    }

    // ---------------------------------------------------------------------------- один relay

    private suspend fun run(url: String) {
        var backoff = INITIAL_BACKOFF
        while (running && scope.isActive) {
            val relay = Relay(url)
            relay.socket = client.newWebSocket(Request.Builder().url(url).build(), relay)

            // Подключённым считается, только когда ответил на рукопожатие. Сокет, который ещё
            // здоровается — или к relay'ю, который отвечает на него `502`, — получал бы события,
            // которые не доставит, и `publish` верил бы, что relay есть. На этом же держится и число
            // relay'ев на экране «Подключение».
            if (withTimeoutOrNull(10_000) { relay.opened.await() } == true) {
                sockets[url] = relay
                relay.socket.send("""["REQ","$subscriptionId",$filterJson]""")
                relay.closed.await()
                if (relay.heardAnything) backoff = INITIAL_BACKOFF
            }

            drop(relay, url)
            if (!running) return
            backoff = if (nap(backoff)) INITIAL_BACKOFF else minOf(backoff * 2, MAX_BACKOFF)
        }
    }

    /** Проспать backoff или до [wake], что раньше. True — разбудили. */
    private suspend fun nap(millis: Long): Boolean {
        val started = wakes.value
        return withTimeoutOrNull(millis) { wakes.first { it != started } } != null
    }

    /** Больше не считать сокет подключённым и закрыть его, чтобы и читатель сдался. По тождеству: заменённый не снимает заменивший. */
    private fun drop(relay: Relay, url: String) {
        sockets.remove(url, relay)
        relay.cancel()
    }

    /**
     * Одно сообщение relay'я. Это JSON-массив, и первый элемент его называет: `EVENT`, `OK`, `EOSE`,
     * `NOTICE`, `CLOSED`. Что-то несёт только `EVENT`; остальное отмечается и выбрасывается — смысл
     * имеет только ответ самого туннеля.
     */
    internal fun handle(message: String, url: String) {
        val array = runCatching { Json.parseToJsonElement(message).jsonArray }.getOrNull() ?: return
        when ((array.firstOrNull() as? JsonPrimitive)?.content) {
            "EVENT" -> {
                val event = array.getOrNull(2) as? JsonObject ?: return
                val id = (event["id"] as? JsonPrimitive)?.content ?: return
                if (synchronized(seen) { id in seen }) return
                val json = event.toString()
                // Проверяется *до* того, как запомнено: иначе враждебный relay, приславший первым
                // подделку с id настоящего ответа моста, сделал бы настоящие копии со всех честных
                // relay'ев «дубликатами» — и избыточность трёх relay'ев отменил бы один.
                if (verify(json) != id) {
                    Log.warn(Log.Category.BRIDGE, "relay прислал событие, которое не проверяется")
                    return
                }
                synchronized(seen) {
                    if (!seen.add(id)) return
                    if (seen.size > SEEN_LIMIT) seen.remove(seen.first())
                }
                onEvent(json)
            }
            // Relay говорит «нет» — обычно ограничение частоты. Видно в логе, но не само сообщение:
            // это строка с чужого сервера.
            "NOTICE", "CLOSED" -> Log.info(Log.Category.BRIDGE, "relay ${host(url)} сказал ${(array[0] as JsonPrimitive).content}")
            else -> Unit
        }
    }

    private fun host(url: String) = runCatching { URI(url).host }.getOrNull() ?: "?"

    /** Один сокет к одному relay'ю. */
    private inner class Relay(private val url: String) : WebSocketListener() {
        lateinit var socket: WebSocket
        val opened = CompletableDeferred<Boolean>()
        val closed = CompletableDeferred<Unit>()

        @Volatile
        var heardAnything = false

        /** Растёт с каждым сообщением relay'я — для [answers]. */
        private val messages = MutableStateFlow(0L)

        /** Ответит ли relay на короткий вопрос за [within] мс. */
        suspend fun answers(within: Long): Boolean {
            val before = messages.value
            val probe = "ping-" + UUID.randomUUID().toString().take(8)
            if (!socket.send("""["REQ","$probe",{"ids":["${"0".repeat(64)}"],"limit":0}]""")) return false
            val answered = withTimeoutOrNull(within) { messages.first { it != before } } != null
            socket.send("""["CLOSE","$probe"]""")
            return answered
        }

        fun cancel() {
            socket.cancel()
            opened.complete(false)
            closed.complete(Unit)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(true)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            heardAnything = true
            messages.value += 1
            handle(text, url)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closed.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened.complete(false)
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Обычное дело: relay'и уходят, Wi-Fi меняется, телефон достают из кармана.
            opened.complete(false)
            closed.complete(Unit)
        }
    }

    companion object {
        private const val INITIAL_BACKOFF = 1_000L
        private const val MAX_BACKOFF = 30_000L
        private const val SEEN_LIMIT = 512

        /**
         * Клиент relay'ев: без таймаута чтения — сокет молчит, пока моста не о чем спрашивать, — и с
         * ping'ом раз в двадцать секунд, который роняет соединение, переставшее отвечать.
         */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
