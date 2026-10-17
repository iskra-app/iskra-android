package moscow.swag.iskra.core.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moscow.swag.iskra.core.support.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** Запрос, как его получил сервер: метод, цель (путь с запросом), заголовки, тело. */
internal class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

internal class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/**
 * Простой HTTP/1.1 на loopback — то, за чем на iOS стоит `LocalHTTPProxy` с `HTTPUpstream`. SDK
 * ходит сюда, как на любой сервер, и не знает, что ответы придумывает [handler] в этом же процессе.
 *
 * Без зависимостей: Matrix от клиента — это JSON с `Content-Length` (иногда chunked), keep-alive и
 * больше ничего. Только `127.0.0.1`: снаружи устройства этого сервера не видно. Мост (этап 9)
 * поставит сюда настоящий прокси с тем же устройством.
 */
internal class LoopbackHttpServer(
    private val handler: suspend (HttpRequest) -> HttpResponse,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null

    /** Порт, на котором сервер слушает; поднимает его при первом вызове. */
    @Synchronized
    fun start(): Int {
        socket?.let { return it.localPort }
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        socket = server
        scope.launch {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                scope.launch { serve(client) }
            }
        }
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    private fun serve(client: Socket) {
        client.use { connection ->
            connection.tcpNoDelay = true
            val input = BufferedInputStream(connection.getInputStream())
            val output = connection.getOutputStream()
            while (true) {
                val request = try {
                    read(input) ?: return
                } catch (_: IOException) {
                    return
                }
                val response = try {
                    // Поток соединения и так свой: ожидание ответа (long-poll синхронизации) держит
                    // только его, а не сервер.
                    runBlocking { handler(request) }
                } catch (error: Exception) {
                    Log.error(Log.Category.SESSION, "демо-сервер упал на запросе: ${Log.describe(error)}")
                    HttpResponse(STATUS_ERROR, mapOf("Content-Type" to "application/json"), "{}".toByteArray())
                }
                try {
                    write(output, response)
                } catch (_: IOException) {
                    return
                }
                if (request.header("Connection").equals("close", ignoreCase = true)) return
            }
        }
    }

    private fun read(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) throw IOException("bad request line")

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("eof in headers")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        val body = when {
            header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            else -> readExactly(input, header("Content-Length")?.toIntOrNull() ?: 0)
        }
        return HttpRequest(parts[0], parts[1], headers, body)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = (readLine(input) ?: throw IOException("eof in chunk")).substringBefore(';').trim().toInt(HEX)
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) Unit
                return out.toByteArray()
            }
            out.write(readExactly(input, size))
            readLine(input)
        }
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read < 0) throw IOException("eof in body")
            offset += read
        }
        return buffer
    }

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    private fun write(output: OutputStream, response: HttpResponse) {
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        for ((name, value) in response.headers) head.append(name).append(": ").append(value).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Connection: keep-alive\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        output.write(response.body)
        output.flush()
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        409 -> "Conflict"
        else -> "Status"
    }

    private companion object {
        const val BACKLOG = 32
        const val HEX = 16
        const val STATUS_ERROR = 500
    }
}
