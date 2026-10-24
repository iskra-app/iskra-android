package wtf.iskra.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Одно TCP-соединение как байты: то, что [LocalConnectProxy] отдаёт тому, кто несёт его дальше.
 * Порт `LocalSocket.swift`.
 *
 * Блокирующий `java.net.Socket` на `Dispatchers.IO`: чтение и запись идут в разных корутинах, и
 * сокет это позволяет — у него два потока, вход и выход, и каждый трогает одна корутина.
 */
class LocalSocket(private val socket: Socket) {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    /** Следующие байты, сколько пришло, до [maximum]. Null, когда другая сторона закрыла свою половину. */
    suspend fun receive(maximum: Int = 64 * 1024): ByteArray? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(maximum)
        val count = input.read(buffer)
        if (count < 0) null else buffer.copyOf(count)
    }

    suspend fun send(bytes: ByteArray) = withContext(Dispatchers.IO) {
        output.write(bytes)
        output.flush()
    }

    /** Закрыть свою половину: другая сторона читает конец потока и может ещё писать. */
    suspend fun finishWriting() = withContext(Dispatchers.IO) {
        runCatching { socket.shutdownOutput() }
        Unit
    }

    /** Закрыть по порядку. */
    fun close() {
        runCatching { socket.close() }
    }

    /**
     * Закрыть сразу, чтобы другая сторона увидела сброс, а не обычный конец: тогда reqwest
     * повторяет запрос на свежем соединении, а не читает обрезанный ответ.
     */
    fun reset() {
        runCatching { socket.setSoLinger(true, 0) }
        close()
    }

    /** Порт этого конца. По нему тест узнаёт, что соединения на дальнем листенере пришли из трубы. */
    val localPort: Int get() = socket.localPort

    companion object {
        /** Соединение наружу, к `host:port`; null — не поднялось. */
        suspend fun connect(host: String, port: Int, timeoutMillis: Int = 15_000): LocalSocket? = withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.tcpNoDelay = true
                LocalSocket(socket)
            } catch (_: IOException) {
                runCatching { socket.close() }
                null
            }
        }
    }
}

/**
 * Сокет, прочитанный сегментами: что пришло без паузы, до размера, — `next_segment` клиента на
 * Rust и `SegmentReader` на iOS, то же правило.
 *
 * Склейка — главное. Сокет отдаёт TLS-записи по несколько сотен байт, и событие на каждое чтение —
 * это сотни событий на открытие комнаты и бан от relay'я, который держит волонтёр. Поэтому сегмент
 * уходит, когда набралось [maximum] или сокет молчал [coalesceMillis].
 *
 * Читать вперёд можно не больше [readAhead] чтений: дальше у сокета ничего не просят, пока владелец
 * не заберёт прочитанное. Так полное окно останавливает чтение, и TCP доносит обратную связь до того,
 * кто пишет.
 */
class SegmentReader(
    private val socket: LocalSocket,
    leftover: ByteArray = ByteArray(0),
    scope: CoroutineScope,
    readAhead: Int = 4,
) {
    sealed interface Read {
        class Segment(val bytes: ByteArray) : Read
        data object End : Read
        data object Failed : Read
    }

    private val chunks = Channel<ByteArray>(readAhead)

    /** Недобранное в прошлый раз: кусок, который не влез в сегмент целиком. */
    private var pending: ByteArray = leftover
    private var ended = false
    private var failed = false

    private val reading: Job = scope.launch {
        try {
            while (true) {
                val bytes = socket.receive() ?: break
                if (bytes.isNotEmpty()) chunks.send(bytes)
            }
            chunks.close()
        } catch (error: IOException) {
            chunks.close(error)
        }
    }

    fun cancel() {
        reading.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun next(maximum: Int, coalesceMillis: Long): Read {
        val segment = java.io.ByteArrayOutputStream(maximum)
        fun take(bytes: ByteArray) {
            val room = maximum - segment.size()
            if (bytes.size <= room) {
                segment.write(bytes)
            } else {
                segment.write(bytes, 0, room)
                pending = bytes.copyOfRange(room, bytes.size)
            }
        }

        if (pending.isNotEmpty()) {
            val first = pending
            pending = ByteArray(0)
            take(first)
        } else {
            if (ended) return Read.End
            if (failed) return Read.Failed
            val first = chunks.receiveCatching()
            when {
                first.isSuccess -> take(first.getOrThrow())
                first.exceptionOrNull() != null -> return Read.Failed.also { failed = true }
                else -> return Read.End.also { ended = true }
            }
        }

        // Добирать, пока сокет не замолчал на склейку или сегмент не полон. `select` с таймаутом, а не
        // `withTimeout` вокруг `receive`: отменённый `receive` может потерять уже взятый кусок.
        while (segment.size() < maximum && pending.isEmpty() && !ended && !failed) {
            val more = select<kotlinx.coroutines.channels.ChannelResult<ByteArray>?> {
                chunks.onReceiveCatching { it }
                onTimeout(coalesceMillis) { null }
            } ?: break
            when {
                more.isSuccess -> take(more.getOrThrow())
                more.exceptionOrNull() != null -> failed = true
                else -> ended = true
            }
        }
        return Read.Segment(segment.toByteArray())
    }
}
