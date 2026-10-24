package wtf.iskra.core.transport.nostr

import wtf.iskra.tunnel.powRequired

/**
 * Что сказал мост, когда кончил поток, — так, чтобы экран мог сказать это словами. Порт `BridgeRefusal.swift`.
 *
 * У моста одно слово на каждый способ кончить поток (`reason` в `iskra-tunnel-proto/src/stream.rs`), и
 * новости это разные. Одни значат *мост туда не повезёт* — и совет тогда про список моста или про
 * сервер, а не про адрес, который напечатал человек. Одно значит *мост занят*, и это пройдёт. Остальное —
 * соединение порвалось по дороге: виновата сеть или ошибка, и говорится так, со словом моста рядом.
 *
 * Покрывает все слова, что шлёт мост, — `BridgeRefusalTest` держит это по самому `stream.rs`: слово,
 * добавленное на стороне Rust без случая здесь, дошло бы до экрана как «порвалось», когда оно значит
 * то, с чем человек может что-то сделать.
 */
sealed interface BridgeRefusal {
    /** Сервера нет в списке этого моста (`ALLOWED_UPSTREAMS`). */
    data object NotAllowed : BridgeRefusal

    /** Открытый мост спросил сервер, Matrix ли он, и тот не сказал «да». */
    data object NotMatrix : BridgeRefusal

    /** Сервер на порту не 443, а мост возит только 443. */
    data object BadPort : BridgeRefusal

    /** Адрес сервера — в частной сети, куда открытый мост не ходит. */
    data object NotPublic : BridgeRefusal

    /** Мост не нашёл сервер по этому имени. */
    data object NoSuchHost : BridgeRefusal

    /** Мост не смог соединиться с сервером. */
    data object Unreachable : BridgeRefusal

    /** У моста открыто столько потоков, сколько он держит. */
    data object Busy : BridgeRefusal

    /**
     * Мост не узнал в присланном TLS для этого сервера — `not tls`, `no sni`, `sni mismatch`. Никогда
     * не дело рук человека: две программы не договорились.
     */
    data object Disagreement : BridgeRefusal

    /** Поток порвался по дороге, со словом моста о том, как. */
    data class Broke(val reason: String) : BridgeRefusal

    /** Мост не повезёт это устройство к этому серверу — ответ, который решает вход и к сети отношения не имеет. */
    val isRefusal: Boolean
        get() = when (this) {
            NotAllowed, NotMatrix, BadPort, NotPublic, NoSuchHost -> true
            Unreachable, Busy, Disagreement, is Broke -> false
        }

    companion object {
        fun of(reason: String): BridgeRefusal {
            val words = Limits.reasons
            return when (reason) {
                words.notAllowed -> NotAllowed
                words.notMatrix -> NotMatrix
                words.badPort -> BadPort
                words.notPublic -> NotPublic
                words.noSuchHost -> NoSuchHost
                words.unreachable -> Unreachable
                words.busy -> Busy
                words.notTls, words.noSni, words.sniMismatch -> Disagreement
                // Мост, поднявший цену соединения, — мост под нагрузкой; следующий `open` её заплатит,
                // никого не спрашивая.
                else -> if (powRequired(reason) != null) Busy else Broke(reason)
            }
        }
    }
}
