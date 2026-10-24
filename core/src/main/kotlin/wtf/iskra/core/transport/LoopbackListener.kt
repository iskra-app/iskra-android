package wtf.iskra.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wtf.iskra.core.support.Log
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * TCP-листенер на `127.0.0.1`, который поднимается сам, если его потеряли. Порт `LoopbackListener.swift`.
 *
 * SDK знает сервер только как `127.0.0.1:<этот порт>`: клиент собран с ним и другого ему не сказать.
 * На iOS система отбирает слушающие сокеты у приостановленного приложения; на Android процесс в Doze
 * или в кэше теряет сеть, а листенер может закрыться, не спросив (docs/PROMPT.md, 9.5). Снаружи это
 * выглядит как «нет сети», пока мост на том конце отвечает исправно. Поэтому листенер следит за
 * собой всю жизнь и **поднимается снова на том же порту**, а [ensureListening] просит то же самое
 * нарочно — при возвращении на передний план, *до* того, как синхронизация пойдёт снова.
 *
 * Привязан к loopback явно: листенер на `0.0.0.0` — открытая дверь в чужой сеанс для всего в той же Wi-Fi.
 */
class LoopbackListener(
    private val scope: CoroutineScope,
    private val onConnection: (Socket) -> Unit,
) {
    private val lock = Mutex()
    private var server: ServerSocket? = null
    private var accepting: Job? = null

    /** Порт, который знает SDK. Не меняется до [stop]. */
    @Volatile
    var port: Int? = null
        private set

    /** Выставляется только [stop]: листенер, закончившийся без этого, потерян и поднимается снова. */
    @Volatile
    private var stopped = false

    /** Привязаться и вернуть порт, который выбрала система: зашитый порт рано или поздно окажется занят. */
    suspend fun start(): Int = lock.withLock {
        stopped = false
        port?.let { if (server?.isClosed == false) return it }
        val bound = bind(0)
        port = bound.localPort
        bound.localPort
    }

    suspend fun stop() = lock.withLock {
        stopped = true
        accepting?.cancel()
        runCatching { server?.close() }
        server = null
        port = null
    }

    /** Убедиться, что листенер на месте, и поднять его на том же порту, если нет. */
    suspend fun ensureListening() {
        if (stopped || port == null) return
        if (server?.isClosed == false && accepting?.isActive == true) return
        relisten()
    }

    /** Как выглядит потеря изнутри: сокет закрылся, а `stop()` никто не звал. Для тестов. */
    fun simulateLoss() {
        runCatching { server?.close() }
    }

    private fun bind(requested: Int): ServerSocket {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), requested), BACKLOG)
        server = socket
        accepting = scope.launch(Dispatchers.IO) { accept(socket) }
        return socket
    }

    private suspend fun accept(socket: ServerSocket) {
        while (scope.isActive) {
            val connection = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }
            connection.tcpNoDelay = true
            onConnection(connection)
        }
        // Только текущий: заменённый или закрытый `stop()` сообщает о своём конце тоже.
        if (!stopped && server === socket) {
            Log.warn(Log.Category.BRIDGE, "локальный листенер потерян; поднимаю его снова на том же порту")
            scope.launch { relisten() }
        }
    }

    /** Привязаться снова к порту, который SDK уже знает. Несколько попыток: порт освобождается не сразу. */
    private suspend fun relisten() = lock.withLock {
        val known = port ?: return@withLock
        if (stopped || server?.isClosed == false && accepting?.isActive == true) return@withLock
        runCatching { server?.close() }
        for (attempt in 1..5) {
            if (stopped) return@withLock
            try {
                bind(known)
                return@withLock
            } catch (error: IOException) {
                Log.warn(Log.Category.BRIDGE, "листенер не поднялся (попытка $attempt): ${Log.describe(error)}")
                delay(200L * attempt)
            }
        }
    }

    private companion object {
        const val BACKLOG = 64
    }
}
