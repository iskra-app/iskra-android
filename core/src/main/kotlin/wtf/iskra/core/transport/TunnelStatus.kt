package wtf.iskra.core.transport

import wtf.iskra.core.transport.nostr.BridgeRefusal
import wtf.iskra.core.transport.nostr.Heard

/**
 * Что транспорт делает на самом деле — для экрана. Порт `TunnelStatus` из `TunnelSettings.swift`.
 *
 * Каждый случай говорит, что верно о трафике *сейчас*, а не что выбрано. Статус, который обещал бы
 * защиту, которой нет, — худшая ложь, какую может сказать приложение такого рода; поэтому
 * переключатель без адреса моста — [Off]: на пути ничего нет, SDK говорит с сервером напрямую.
 */
sealed interface TunnelStatus {
    /** Моста нет: SDK говорит с сервером напрямую. */
    data object Off : TunnelStatus

    /**
     * Туннель поднят, relay'ев на связи столько-то, а мост ещё не соединил ни одного потока. О том,
     * чего он не видит, не говорится ничего, пока он ничего не пронёс.
     */
    data class Connecting(val relays: Int) : TunnelStatus

    /**
     * Мост соединяет потоки этого устройства: каждое соединение уходит зашифрованными событиями
     * nostr, а TLS внутри — собственный, до самого сервера. Единственный случай, когда экран может
     * сказать «мост не видит ваших сообщений» (docs/PROMPT.md, 9.7): это верно о трафике, который
     * идёт сейчас, а не о настройке.
     */
    data class BridgedBlind(val relays: Int) : TunnelStatus

    /** Мост ответил и не повезёт это устройство к его серверу. */
    data class Refused(val refusal: BridgeRefusal) : TunnelStatus

    /** Листенер не поднялся, или до моста не достучаться. */
    data class Failed(val reason: String) : TunnelStatus

    companion object {
        /**
         * Статус поднятого туннеля по тому, что мост уже сказал. Последний решённый поток выбирает
         * между «отказ» и остальным: мост, отвергший последний поток, отказывает *сейчас*, даже если
         * пронёс прежний; а «не видит» ждёт потока, который мост действительно соединил.
         */
        fun of(heard: Heard, relays: Int): TunnelStatus {
            val last = heard.lastSettled
            if (last is Heard.Last.Reset) {
                val refusal = BridgeRefusal.of(last.reason)
                if (refusal.isRefusal) return Refused(refusal)
            }
            return if (heard.everOpened) BridgedBlind(relays) else Connecting(relays)
        }
    }
}
