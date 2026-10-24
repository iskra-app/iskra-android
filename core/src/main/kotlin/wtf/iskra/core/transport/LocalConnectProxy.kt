package wtf.iskra.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.OkHttpClient
import wtf.iskra.core.support.TimeoutError
import wtf.iskra.core.support.withTimeLimit
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Что несёт соединение после `CONNECT` дальше: туннель nostr в приложении, обычный TCP ([DirectPipe]) в тестах. */
fun interface ConnectUpstream {
    /**
     * Нести [socket] к `host:port`, пока одна из сторон не закончит. [leftover] — то, что клиент
     * прислал после заголовка `CONNECT`, не дожидаясь `200`: начало ClientHello, обычно ничего.
     */
    suspend fun carry(socket: LocalSocket, host: String, port: Int, leftover: ByteArray)
}

/**
 * Локальный конец слепого моста: HTTP-прокси на `127.0.0.1`, который отвечает на `CONNECT` и ни на
 * что больше. Порт `LocalConnectProxy.swift`.
 *
 * SDK собирается с *настоящим* адресом сервера и этим прокси (`ClientBuilder.proxy`). reqwest делает
 * то, что делает с любым `https`-адресом за `http`-прокси: шлёт `CONNECT matrix-client.matrix.org:443`
 * и ведёт через соединение свой TLS, проверяя настоящий сертификат. Сюда после `200` приходит
 * шифротекст — ровно то, что можно отдать мосту, которому видеть нельзя.
 *
 * **`Proxy-Authorization` обязателен.** В loopback-порт может постучать что угодно на телефоне, и без
 * него это возило бы трафик любого приложения через мост человека. Секрет случайный на листенер и
 * живёт в адресе прокси (`http://iskra:<секрет>@127.0.0.1:<порт>`); reqwest кладёт его в `CONNECT` сам,
 * а `CONNECT` без него получает `407`.
 *
 * `200` уходит сразу, раньше, чем дальний конец сказал хоть слово: ClientHello не придёт, пока его нет,
 * а ClientHello и едет в `open` — иначе холодное соединение платило бы ещё одним кругом через relay.
 */
class LocalConnectProxy(
    private val upstream: ConnectUpstream,
    private val secret: String = newSecret(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener = LoopbackListener(scope) { accept(it) }

    /** Пропущенные `CONNECT`. */
    val carried = AtomicInteger()

    /** Отвергнутые: не `CONNECT` или без секрета. */
    val refused = AtomicInteger()

    val port: Int? get() = listener.port

    /** Адрес для `ClientBuilder.proxy`. */
    val proxyUrl: String? get() = port?.let { "http://$USER:$secret@127.0.0.1:$it" }

    suspend fun start(): Int = listener.start()

    suspend fun stop() {
        listener.stop()
        scope.cancel()
    }

    /** См. [LoopbackListener.ensureListening]. */
    suspend fun ensureListening() = listener.ensureListening()

    fun simulateLoss() = listener.simulateLoss()

    /**
     * OkHttp, который ходит через этот листенер, с секретом, — для своих запросов приложения
     * (устройства, присутствие, проверка моста): они идут *тем же путём*, что запросы SDK (docs/PROMPT.md,
     * 9.6). Не слушает — клиент без выхода наружу, а не клиент, который идёт напрямую.
     */
    fun httpClient(base: OkHttpClient = OkHttpClient()): OkHttpClient {
        val address = InetSocketAddress("127.0.0.1", port ?: CLOSED_PORT)
        val credentials = Credentials.basic(USER, secret)
        return base.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, address))
            // OkHttp спрашивает это и заранее, до первого `CONNECT` («OkHttp-Preemptive»), — так прокси
            // не отвечает `407` на каждое соединение.
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) {
                    null
                } else {
                    response.request.newBuilder().header("Proxy-Authorization", credentials).build()
                }
            }
            .cache(null)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .build()
    }

    private fun accept(connection: Socket) {
        val expected = "Basic " + Base64.getEncoder().encodeToString("$USER:$secret".toByteArray())
        scope.launch {
            val socket = LocalSocket(connection)
            val head = try {
                readHead(socket)
            } catch (_: IOException) {
                null
            } catch (_: TimeoutError) {
                null
            }
            if (head == null) {
                socket.close()
                return@launch
            }
            when (val answer = answer(head.first, expected)) {
                is Answer.Refuse -> {
                    refused.incrementAndGet()
                    runCatching { socket.send(answer.response.toByteArray()) }
                    socket.close()
                }
                is Answer.Connect -> {
                    carried.incrementAndGet()
                    try {
                        socket.send("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                    } catch (_: IOException) {
                        socket.close()
                        return@launch
                    }
                    upstream.carry(socket, answer.host, answer.port, head.second)
                }
            }
        }
    }

    sealed interface Answer {
        data class Connect(val host: String, val port: Int) : Answer
        data class Refuse(val response: String) : Answer
    }

    companion object {
        const val USER = "iskra"

        /** Заголовок `CONNECT` — строка и несколько полей. Потолок: loopback — это любой процесс на телефоне. */
        const val MAX_HEAD = 8 * 1024

        /** И приходит он сразу или никогда. */
        private val HEAD_TIMEOUT = 10.seconds

        /** Порт, на котором никого нет: клиент прокси, который не слушает, никуда не ходит. */
        private const val CLOSED_PORT = 9

        /** 32 случайных байта, hex. Живёт в памяти и в адресе прокси клиента, больше нигде. */
        fun newSecret(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** Заголовок до пустой строки и то, что пришло после него. */
        private suspend fun readHead(socket: LocalSocket): Pair<String, ByteArray>? = withTimeLimit(HEAD_TIMEOUT) {
            var buffer = ByteArray(0)
            var end = indexOfBlankLine(buffer)
            while (end < 0) {
                if (buffer.size > MAX_HEAD) return@withTimeLimit null
                buffer += socket.receive(4096) ?: return@withTimeLimit null
                end = indexOfBlankLine(buffer)
            }
            String(buffer, 0, end, Charsets.ISO_8859_1) to buffer.copyOfRange(end + 4, buffer.size)
        }

        private fun indexOfBlankLine(bytes: ByteArray): Int {
            for (i in 0..bytes.size - 4) {
                if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte()
                ) {
                    return i
                }
            }
            return -1
        }

        /** Что ответить на заголовок. Отдельно от сокета, чтобы отказы проверялись как текст. */
        fun answer(head: String, expected: String): Answer {
            val lines = head.split("\r\n")
            val parts = lines.firstOrNull().orEmpty().split(' ')
            if (parts.size != 3 || parts[0] != "CONNECT") {
                return Answer.Refuse("HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n")
            }
            val given = lines.drop(1).firstNotNullOfOrNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0 || !line.substring(0, colon).trim().equals("proxy-authorization", ignoreCase = true)) {
                    null
                } else {
                    line.substring(colon + 1).trim()
                }
            }
            if (given == null || !MessageDigest.isEqual(given.toByteArray(), expected.toByteArray())) {
                return Answer.Refuse(
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"iskra\"\r\nConnection: close\r\n\r\n",
                )
            }
            val (host, port) = splitAuthority(parts[1])
                ?: return Answer.Refuse("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
            return Answer.Connect(host, port)
        }

        /** `matrix.org:443` → (`matrix.org`, 443); `[::1]:8448` → (`::1`, 8448). Без порта — ничего. */
        fun splitAuthority(authority: String): Pair<String, Int>? {
            val colon = authority.lastIndexOf(':')
            if (colon < 0) return null
            val port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            var host = authority.substring(0, colon)
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
            if (host.isEmpty()) return null
            return host to port
        }
    }
}

/**
 * `CONNECT`, который несёт обычное TCP-соединение, открытое прямо здесь, — без моста и без nostr.
 *
 * Для тестов: доказывает, что SDK, собранный с прокси, действительно шлёт через него *всё*, ещё до
 * того, как в деле мост. [ports] — улика: локальный порт каждого соединения, которое он открыл.
 */
class DirectPipe : ConnectUpstream {
    private val made = java.util.Collections.synchronizedList(mutableListOf<Int>())

    val ports: List<Int> get() = synchronized(made) { made.toList() }

    override suspend fun carry(socket: LocalSocket, host: String, port: Int, leftover: ByteArray) {
        val remote = LocalSocket.connect(host, port) ?: return socket.reset()
        made += remote.localPort
        if (leftover.isNotEmpty()) {
            try {
                remote.send(leftover)
            } catch (_: IOException) {
                remote.reset()
                return socket.reset()
            }
        }
        coroutineScope {
            launch { copy(socket, remote) }
            launch { copy(remote, socket) }
        }
        socket.close()
        remote.close()
    }

    /** Одно направление, до конца. Сбой кончает оба: наполовину сломанная труба — сломанная. */
    private suspend fun copy(source: LocalSocket, sink: LocalSocket) {
        try {
            while (true) {
                val bytes = source.receive() ?: return sink.finishWriting()
                if (bytes.isNotEmpty()) sink.send(bytes)
            }
        } catch (_: IOException) {
            source.reset()
            sink.reset()
        }
    }
}

/** Время ожидания соединения для своих запросов приложения: тот же порядок, что у SDK. */
internal fun OkHttpClient.Builder.applyTimeouts(): OkHttpClient.Builder =
    connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
