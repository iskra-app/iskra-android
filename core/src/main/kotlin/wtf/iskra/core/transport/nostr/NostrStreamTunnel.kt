package wtf.iskra.core.transport.nostr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import wtf.iskra.core.transport.ConnectUpstream
import wtf.iskra.core.transport.LocalSocket
import wtf.iskra.tunnel.Frame
import wtf.iskra.tunnel.FrameOp
import wtf.iskra.tunnel.FrameSealer
import wtf.iskra.tunnel.TunnelException
import wtf.iskra.tunnel.powRequired
import wtf.iskra.tunnel.verifyEvent
import java.util.concurrent.atomic.AtomicInteger

/**
 * Клиентская половина слепого моста: каждое соединение, пропущенное [wtf.iskra.core.transport.LocalConnectProxy],
 * несётся потоком зашифрованных событий nostr (протокол v2, kinds 20010 и 20011). Порт `NostrStreamTunnel.swift`.
 *
 * Внутри — собственный TLS SDK: мост соединяется с сервером и двигает шифротекст, видя хост, время и
 * число байт. Протокол до этого отдавал мосту каждый запрос, токен и всё; его нет с обеих сторон.
 *
 * Две вещи, выученные дорого, держатся и здесь:
 *
 * - **Один диспетчер на все потоки.** События приходят одной подпиской и раздаются в порядке прихода
 *   потоку, чей id они называют. Читатель на поток — та квадратичная форма, из которой первый клиент
 *   пришлось вытаскивать.
 * - **Ничто не ждёт `OK` relay'я.** Кадр отдаётся во все открытые сокеты, и всё; ждут `ack` другой стороны.
 *
 * @param relaysFor пул relay'ев для фильтра и получателя событий: [NostrRelayPool] в приложении,
 *   relay в памяти в тестах.
 * @throws TunnelException.NotAKey адрес моста или ключ устройства — не ключ.
 */
class NostrStreamTunnel(
    bridgeKeyHex: String,
    secretKey: ByteArray,
    private val relaysFor: (filter: String, onEvent: (String) -> Unit) -> Relays,
    maximumSegment: Int = Limits.segment,
) : ConnectUpstream {
    constructor(bridgeKeyHex: String, relays: List<String>, secretKey: ByteArray) : this(
        bridgeKeyHex,
        secretKey,
        { filter, onEvent -> NostrRelayPool(relays, filter, ::verifyEvent, onEvent) },
    )

    private val sealer = FrameSealer.forClient(secretKey, bridgeKeyHex)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var relays: Relays? = null
    private var dispatcher: Job? = null

    /** Что услышано от моста: для проверки, для строки статуса и чтобы отличить отказ моста от отказа сервера. */
    val heard = Heard()

    /**
     * Биты NIP-13, под которые майнится каждый `open`: по умолчанию, пока мост не откажет `pow <bits>`,
     * и сколько попросил — после. Помнится на жизнь туннеля: мост, поднявший цену под атакой, к
     * следующему запуску обычно её уже опустил.
     */
    private val powBits = AtomicInteger(Limits.defaultPowBits)

    private val carrier = StreamCarrier(
        link = { frame -> publish(frame) },
        background = scope,
        maximumSegment = maximumSegment,
    )

    /** Этот ли мост: настройки, поменявшие адрес, поднимают новый туннель, а не берут старый. */
    val bridgeKeyHex: String = sealer.peerPublicKeyHex()

    private suspend fun publish(frame: Frame) {
        val bits = if (frame.op == FrameOp.OPEN) powBits.get() else 0
        // Доказательство работы — доли секунды на телефоне, раз на соединение: не на потоке ввода-вывода.
        val event = withContext(Dispatchers.Default) { sealer.seal(frame, bits.toUByte()) }
        (relays ?: throw NoRelayConnected()).publish(event)
    }

    /**
     * Открыть соединения с relay'ями и подписаться на кадры моста. До листенера: сокеты уже здороваются,
     * пока SDK ещё собирается, — иначе первым соединением через холодный туннель было бы то, что ждёт
     * трёх TLS-рукопожатий с relay'ями.
     */
    @Synchronized
    fun start() {
        if (relays != null) return
        val events = Channel<String>(Channel.UNLIMITED)
        val since = System.currentTimeMillis() / 1000 - 60
        val pool = relaysFor(sealer.subscriptionFilter(since.toULong())) { events.trySend(it) }
        relays = pool
        dispatcher = scope.launch {
            for (json in events) {
                // Не наше или не от того, кто назвался. На открытом relay'е обычное дело, и о
                // содержимом такого события не стоит ни слова.
                val frame = sealer.open(json) ?: continue
                heard.frame(frame)
                if (frame.op == FrameOp.RST) frame.reason?.let(::powRequired)?.let { powBits.set(it.toInt()) }
                carrier.deliver(frame)
            }
        }
        pool.start()
    }

    @Synchronized
    fun stop() {
        relays?.stop()
        relays = null
        dispatcher?.cancel()
        dispatcher = null
        scope.cancel()
        sealer.close()
    }

    /** Возвращение с фона: см. [NostrRelayPool.wake]. */
    suspend fun wake() {
        relays?.wake()
    }

    /** Оборвать все соединения с relay'ями разом. Для тестов. */
    fun severConnections() {
        relays?.sever()
    }

    val connectedRelays: Int get() = relays?.connected ?: 0

    val openStreams: Int get() = carrier.open

    override suspend fun carry(socket: LocalSocket, host: String, port: Int, leftover: ByteArray) {
        val outcome = carrier.carry(socket, host, port, leftover)
        // Причина — одно из коротких слов моста: никогда хост, никогда байт того, что неслось.
        if (outcome is StreamOutcome.Reset) Log.info(Log.Category.BRIDGE, "поток через мост сброшен: ${outcome.reason}")
    }
}

/**
 * Что услышано от моста: для [wtf.iskra.core.transport.BridgeCheck], для строки статуса и чтобы
 * отличить вход, который отверг мост, от входа, который отверг сервер. Порт `Heard`.
 */
class Heard {
    /** Последнее, что решило судьбу потока. */
    sealed interface Last {
        data object Opened : Last

        data class Reset(val reason: String) : Last
    }

    private var any = false
    private var opened = false
    private var last: Last? = null
    private var resets = 0

    @Synchronized
    fun frame(frame: Frame) {
        any = true
        when (frame.op) {
            FrameOp.OPENED -> {
                opened = true
                last = Last.Opened
            }
            FrameOp.RST -> frame.reason?.let {
                last = Last.Reset(it)
                resets += 1
            }
            else -> Unit
        }
    }

    /** Пришёл ли от моста хоть один кадр. */
    val anything: Boolean @Synchronized get() = any

    /** Соединял ли мост хоть один поток — это и делает его мостом, несущим TLS устройства, а не ключом на relay'е. */
    val everOpened: Boolean @Synchronized get() = opened

    val lastSettled: Last? @Synchronized get() = last

    /** Последнее слово моста, которым он кончил поток. */
    val lastReset: String? @Synchronized get() = (last as? Last.Reset)?.reason

    /** Место, к которому вернуться: [refusal] отвечает только за то, что было после него. */
    val mark: Int @Synchronized get() = resets

    /** Что сказал мост, если он сбросил поток после [mark] и последнее слово было отказом. */
    @Synchronized
    fun refusal(since: Int): BridgeRefusal? {
        val reset = last as? Last.Reset ?: return null
        return if (resets > since) BridgeRefusal.of(reset.reason) else null
    }
}
