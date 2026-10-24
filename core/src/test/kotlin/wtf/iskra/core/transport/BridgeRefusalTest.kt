package wtf.iskra.core.transport

import wtf.iskra.core.transport.nostr.BridgeRefusal
import wtf.iskra.core.transport.nostr.Heard
import wtf.iskra.tunnel.frameOpened
import wtf.iskra.tunnel.frameRst
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Порт `BridgeRefusalTests`: каждое слово, которым мост кончает поток, становится тем, что человек
 * может понять, — и список слов берётся из самого `stream.rs` вендоренного протокола, а не переписан.
 * Слово, добавленное на стороне Rust без случая в [BridgeRefusal], упадёт здесь, а не на экране.
 */
class BridgeRefusalTest {
    /** `pub const X: &str = "…";` внутри `pub mod reason` — все слова моста. */
    private val bridgeWords: List<String> = run {
        val source = File("../third_party/iskra-tunnel-proto/src/stream.rs").readText()
        val module = source.substringAfter("pub mod reason {").substringBefore("\n}")
        Regex("""pub const \w+: &str = "([^"]+)";""").findAll(module).map { it.groupValues[1] }.toList()
    }

    /** Слова, которые значат «порвалось по дороге», — сеть или ошибка, со словом моста рядом. */
    private val breaks = setOf("gap", "window", "protocol", "upstream", "idle", "expired", "closed")

    @Test
    fun everyWordTheBridgeSendsIsKnown() {
        assertEquals(18, bridgeWords.size, "the reason list in stream.rs changed: $bridgeWords")
        for (word in bridgeWords) {
            // `pow` уходит с числом — `pow 20`: сложность едет в самом отказе.
            val refusal = BridgeRefusal.of(if (word == "pow") "pow 20" else word)
            if (word in breaks) {
                assertEquals(BridgeRefusal.Broke(word), refusal, word)
            } else {
                assertIs<BridgeRefusal>(refusal)
                assertTrue(refusal !is BridgeRefusal.Broke, "«$word» reaches the screen as «broke»")
            }
        }
    }

    @Test
    fun theFiveRefusalsSettleASignIn() {
        val refusals = bridgeWords.map(BridgeRefusal::of).filter { it.isRefusal }.toSet()
        assertEquals(
            setOf(BridgeRefusal.NotAllowed, BridgeRefusal.NotMatrix, BridgeRefusal.BadPort, BridgeRefusal.NotPublic, BridgeRefusal.NoSuchHost),
            refusals,
        )
    }

    @Test
    fun tlsDisagreementsAreOneThing() {
        for (word in listOf("not tls", "no sni", "sni mismatch")) assertEquals(BridgeRefusal.Disagreement, BridgeRefusal.of(word))
    }

    /** Мост, поднявший цену соединения, — занятый мост; потолок выше 24 бит — не цена, а поломка. */
    @Test
    fun aPowDemandIsBusy() {
        assertEquals(BridgeRefusal.Busy, BridgeRefusal.of("pow 20"))
        assertEquals(BridgeRefusal.Broke("pow 99"), BridgeRefusal.of("pow 99"))
    }

    /** Отказ прошлой попытки не вешается на следующую: [Heard.mark]. */
    @Test
    fun aRefusalIsOnlyBlamedOnWhatCameAfterTheMark() {
        val heard = Heard()
        heard.frame(frameRst("a", "not allowed"))
        val mark = heard.mark
        assertNull(heard.refusal(since = mark))
        heard.frame(frameRst("b", "not matrix"))
        assertEquals(BridgeRefusal.NotMatrix, heard.refusal(since = mark))
    }

    /** «Не видит ваших сообщений» — только когда мост действительно соединил поток, и не после отказа. */
    @Test
    fun blindOnlyAfterAStreamWasCarried() {
        val heard = Heard()
        assertEquals(TunnelStatus.Connecting(3), TunnelStatus.of(heard, 3))
        heard.frame(frameOpened("a"))
        assertEquals(TunnelStatus.BridgedBlind(3), TunnelStatus.of(heard, 3))
        heard.frame(frameRst("b", "not allowed"))
        assertEquals(TunnelStatus.Refused(BridgeRefusal.NotAllowed), TunnelStatus.of(heard, 3))
        heard.frame(frameRst("c", "gap"))
        assertFalse(TunnelStatus.of(heard, 3) is TunnelStatus.Refused, "a broken stream is not a refusal")
    }
}
