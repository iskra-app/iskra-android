package wtf.iskra.core.transport.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wtf.iskra.core.transport.LocalSocket
import wtf.iskra.core.transport.SegmentReader
import wtf.iskra.tunnel.Frame
import wtf.iskra.tunnel.FrameOp
import wtf.iskra.tunnel.InboundReassembly
import wtf.iskra.tunnel.OutboundWindow
import wtf.iskra.tunnel.TunnelException
import wtf.iskra.tunnel.frameAck
import wtf.iskra.tunnel.frameData
import wtf.iskra.tunnel.frameFin
import wtf.iskra.tunnel.frameOpen
import wtf.iskra.tunnel.frameRst
import wtf.iskra.tunnel.newStreamId
import wtf.iskra.tunnel.tunnelLimits
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Как кончился поток, насколько это знает эта сторона. */
sealed interface StreamOutcome {
    /** Обе половины закрыты по порядку. */
    data object Closed : StreamOutcome

    /** Сброшен, с причиной — словом другой стороны, если сказала она. */
    data class Reset(val reason: String) : StreamOutcome
}

/**
 * Куда уходят кадры потока: запечатываются и публикуются на relay'и в приложении, отдаются прямо
 * мосту-заглушке в тестах. Бросает, когда кадру некуда идти вовсе.
 */
typealias StreamLink = suspend (Frame) -> Unit

/** Числа протокола для насоса — из крейта, а не переписанные. */
internal object Limits {
    private val limits = tunnelLimits()
    val segment: Int = limits.defaultSegment.toInt()
    val coalesceMillis: Long = limits.coalesceMillis.toLong()
    val defaultPowBits: Int = limits.defaultPowBits.toInt()
    val reasons = wtf.iskra.tunnel.streamReasons()
}

/**
 * Один поток, одна сторона: сокет на этом конце, кадры на том. Порт `StreamPump.swift` (`pump` в
 * клиенте на Rust).
 *
 * Три дела идут разом, и только одному можно трогать запись в сокет:
 *
 * - **отправка** читает сокет сегментами ([SegmentReader]), пока окно открыто, и публикует каждый.
 *   Полное окно останавливает чтение — это и есть обратная связь;
 * - **запись** берёт то, что сборка сделала непрерывным, по порядку, по одной записи, и только
 *   потом сообщает о записанном: подтверждение говорит, что сокет *принял*;
 * - **сторож** даёт дыре таймаут и сбрасывает поток. Повторной передачи нет: reqwest видит сброс и
 *   повторяет запрос на свежем соединении.
 *
 * Окно и сборка — `OutboundWindow` и `InboundReassembly` из крейта, тот же код, что у моста. Клиент и
 * мост различаются только тем, кто шлёт `open`, поэтому мост-заглушка в тестах гоняет этот же класс с
 * [Role.Bridge].
 */
class StreamPump(
    val sid: String,
    private val role: Role,
    private val socket: LocalSocket,
    private val leftover: ByteArray,
    private val link: StreamLink,
    /** Где отправляется `rst`, когда сам поток уже кончился. */
    private val background: CoroutineScope,
    private val maximumSegment: Int = Limits.segment,
) {
    sealed interface Role {
        /** Эта сторона открывает: первый сегмент уходит как `open`, к `host:port`. */
        data class Client(val host: String, val port: Int) : Role

        /** Открыла другая сторона; её `open` приходит через [deliver], как любой кадр. */
        data object Bridge : Role
    }

    private val started = System.nanoTime()
    private val inbound = InboundReassembly()
    private var outbound = OutboundWindow()

    /** Сериализует [deliver] и переходы состояния — то, что на iOS делает actor. */
    private val lock = Mutex()
    private val outcome = CompletableDeferred<StreamOutcome>()
    private val windowOpened = Channel<Unit>(Channel.CONFLATED)

    private sealed interface Write {
        class Bytes(val bytes: ByteArray) : Write
        data object End : Write
    }

    private val writes = Channel<Write>(Channel.UNLIMITED)

    /** Наш `fin` ушёл. */
    private var localDone = false

    /** Их `fin` встречен, и запись в сокет закрыта после последнего байта. */
    private var remoteDone = false

    /** Их поток целиком, и конец поставлен в очередь за последней записью. */
    private var endQueued = false

    private fun now(): ULong = ((System.nanoTime() - started) / 1_000_000).toULong()

    /** Вести поток до конца. */
    suspend fun run(): StreamOutcome {
        val result = coroutineScope {
            val reader = SegmentReader(socket, leftover, this)
            val jobs = listOf(
                launch { sendLoop(reader) },
                launch { writeLoop() },
                launch { watch() },
            )
            val result = outcome.await()
            jobs.forEach { it.cancel() }
            reader.cancel()
            result
        }
        // После конца ни один вызов в Rust уже не начнётся: [deliver] смотрит на исход под тем же замком.
        lock.withLock {
            inbound.close()
            outbound.close()
        }
        return result
    }

    /** Один кадр другой стороны, уже проверенный. */
    suspend fun deliver(frame: Frame) = lock.withLock {
        if (outcome.isCompleted) return@withLock
        val now = now()
        try {
            when (frame.op) {
                FrameOp.OPENED -> Unit
                // Шлёт только клиент. Копия со второго relay'я — обычное дело и ляжет дубликатом сегмента 0.
                FrameOp.OPEN -> {
                    if (role !is Role.Bridge) return@withLock reset(Limits.reasons.protocol)
                    take(0u, frame.data ?: ByteArray(0), now)
                }
                FrameOp.DATA -> take(frame.seq ?: 0u, frame.data ?: ByteArray(0), now)
                FrameOp.FIN -> inbound.finish(frame.seq ?: 0u, now)
                FrameOp.ACK -> {
                    outbound.ack(frame.ack ?: 0u)
                    if (outbound.hasRoom(maximumSegment.toUInt())) windowOpened.trySend(Unit)
                }
                FrameOp.RST -> return@withLock end(StreamOutcome.Reset(frame.reason.orEmpty()))
            }
        } catch (violation: TunnelException.Violation) {
            return@withLock reset(violation.reason)
        } catch (_: TunnelException) {
            return@withLock reset(Limits.reasons.protocol)
        }
        if (inbound.isComplete() && !endQueued) {
            endQueued = true
            writes.trySend(Write.End)
        }
    }

    private fun take(seq: UInt, bytes: ByteArray, now: ULong) {
        for (segment in inbound.accept(seq, bytes, now)) writes.trySend(Write.Bytes(segment))
    }

    // ---------------------------------------------------------------------------- три петли

    private suspend fun sendLoop(reader: SegmentReader) {
        val role = role
        if (role is Role.Client) {
            // Ничего не уходит, пока клиент не заговорит: его первые байты едут в `open`.
            val first = reader.next(maximumSegment, Limits.coalesceMillis) as? SegmentReader.Read.Segment
                ?: return end(StreamOutcome.Closed)
            if (!send(frameOpen(sid, role.host, role.port.toUShort(), first.bytes))) return
            lock.withLock { outbound = OutboundWindow.afterOpen(first.bytes.size.toUInt()) }
        }
        while (!outcome.isCompleted) {
            while (!outcome.isCompleted && !outbound.hasRoom(maximumSegment.toUInt())) windowOpened.receive()
            if (outcome.isCompleted) return
            when (val read = reader.next(maximumSegment, Limits.coalesceMillis)) {
                is SegmentReader.Read.Segment -> {
                    if (outcome.isCompleted) return
                    val seq = outbound.push(read.bytes.size.toUInt())
                    if (!send(frameData(sid, seq, read.bytes))) return
                }
                SegmentReader.Read.End -> {
                    if (outcome.isCompleted || !send(frameFin(sid, outbound.nextSeq()))) return
                    lock.withLock {
                        localDone = true
                        if (remoteDone) end(StreamOutcome.Closed)
                    }
                    return
                }
                SegmentReader.Read.Failed -> return reset(Limits.reasons.closed)
            }
        }
    }

    private suspend fun writeLoop() {
        for (write in writes) {
            when (write) {
                is Write.Bytes -> {
                    try {
                        socket.send(write.bytes)
                    } catch (_: IOException) {
                        return reset(Limits.reasons.closed)
                    }
                    inbound.written(1u, write.bytes.size.toULong())?.let { ack -> send(frameAck(sid, ack)) }
                }
                Write.End -> {
                    socket.finishWriting()
                    lock.withLock {
                        remoteDone = true
                        if (localDone) end(StreamOutcome.Closed)
                    }
                    return
                }
            }
        }
    }

    private suspend fun watch() {
        while (!outcome.isCompleted) {
            delay(WATCH_MILLIS)
            if (!outcome.isCompleted && inbound.gapExpired(now())) reset(Limits.reasons.gap)
        }
    }

    // ---------------------------------------------------------------------------- конец

    /** Опубликовать кадр. Кадр, которому некуда идти, кончает поток: дальний конец не услышит остальное. */
    private suspend fun send(frame: Frame): Boolean = try {
        link(frame)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        end(StreamOutcome.Reset(Limits.reasons.unreachable))
        false
    }

    /** Кончить поток и сказать другой стороне почему. */
    private fun reset(reason: String) {
        if (outcome.isCompleted) return
        val frame = frameRst(sid, reason)
        background.launch { runCatching { link(frame) } }
        end(StreamOutcome.Reset(reason))
    }

    private fun end(how: StreamOutcome) {
        if (!outcome.complete(how)) return
        writes.close()
        if (how is StreamOutcome.Closed) socket.close() else socket.reset()
        windowOpened.trySend(Unit)
    }

    private companion object {
        const val WATCH_MILLIS = 250L
    }
}

/**
 * Все потоки одного туннеля по id: кадры — от одного диспетчера, сокеты — от листенера. Порт
 * `StreamCarrier` из `StreamPump.swift`.
 */
class StreamCarrier(
    private val link: StreamLink,
    private val background: CoroutineScope,
    private val maximumSegment: Int = Limits.segment,
) {
    private val pumps = ConcurrentHashMap<String, StreamPump>()

    val open: Int get() = pumps.size

    /** Вести один поток к `host:port` до конца. Id — свежий UUID, выбранный здесь: это ключ у моста, и выбирать его клиенту. */
    suspend fun carry(socket: LocalSocket, host: String, port: Int, leftover: ByteArray): StreamOutcome =
        run(StreamPump(newStreamId(), StreamPump.Role.Client(host, port), socket, leftover, link, background, maximumSegment))

    /** Поток, который открыла другая сторона, — для моста-заглушки. */
    suspend fun accept(sid: String, socket: LocalSocket): StreamOutcome =
        run(StreamPump(sid, StreamPump.Role.Bridge, socket, ByteArray(0), link, background, maximumSegment))

    /** Насос уже зарегистрирован, а `run` ещё не начат: мост-заглушка регистрирует поток раньше, чем отдаст ему `open`. */
    fun register(pump: StreamPump) {
        pumps[pump.sid] = pump
    }

    suspend fun run(pump: StreamPump): StreamOutcome {
        pumps[pump.sid] = pump
        return try {
            pump.run()
        } finally {
            pumps.remove(pump.sid, pump)
        }
    }

    /**
     * Кадр для одного из потоков. Кадр для потока, которого здесь нет — кончился или не наш, —
     * выбрасывается: поздние копии с медленных relay'ев — обычное дело.
     */
    suspend fun deliver(frame: Frame) {
        pumps[frame.sid]?.deliver(frame)
    }

    fun has(sid: String) = pumps.containsKey(sid)
}
