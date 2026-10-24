package wtf.iskra.core.transport

import wtf.iskra.tunnel.BridgeAddress
import wtf.iskra.tunnel.parseBridgeAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Порт `TunnelSettingsTests`: что поля значат для соединения. */
class TunnelSettingsTest {
    @Test
    fun relaysAreSplitOnCommasAndLines() {
        assertEquals(
            listOf("wss://a.example", "wss://b.example", "wss://c.example"),
            TunnelSettings.parseRelays(" wss://a.example,wss://b.example\n\n wss://c.example "),
        )
    }

    /** `https://` выбрасывается, а не исправляется; `ws://` — только к этой машине. */
    @Test
    fun onlySecureRelaysAndLoopbackPlainOnes() {
        assertEquals(
            listOf("wss://ok.example", "ws://127.0.0.1:7777"),
            TunnelSettings.parseRelays("https://nope.example, ws://plain.example, wss://ok.example, ws://127.0.0.1:7777, not a url"),
        )
    }

    @Test
    fun aBareNameMeansHttpsNeverHttp() {
        assertEquals("https://matrix.org", TunnelSettings.upstreamUrl(" matrix.org "))
        assertEquals("http://127.0.0.1:8008", TunnelSettings.upstreamUrl("http://127.0.0.1:8008"))
        assertNull(TunnelSettings.upstreamUrl("  "))
    }

    @Test
    fun loopbackIsThisMachineOnly() {
        assertTrue(TunnelSettings.isLoopback("http://127.0.0.1:8008"))
        assertTrue(TunnelSettings.isLoopback("http://[::1]:8008"))
        assertTrue(TunnelSettings.isLoopback("http://localhost"))
        assertTrue(!TunnelSettings.isLoopback("https://matrix.org"))
    }

    /** Поле по умолчанию держит мост Искры — включить переключатель достаточно. */
    @Test
    fun theDefaultAddressIsABridge() {
        assertNotNull(TunnelSettings.bridgeKeyOf(TunnelSettings.DEFAULT_ADDRESS))
        assertTrue(TunnelSettings.DEFAULT_RELAYS.size > 1, "one relay is a mistake, not a simplification")
        assertEquals(TunnelSettings.DEFAULT_RELAYS, TunnelSettings.parseRelays(TunnelSettings.DEFAULT_RELAYS.joinToString(",")))
    }

    /** Опечатка — не «адреса нет»: иначе включённый мост тихо пошёл бы напрямую. */
    @Test
    fun aTypoIsNotAnEmptyField() {
        assertNull(TunnelSettings.bridgeKeyOf("npub1typo"))
        assertIs<BridgeAddress.NotAKey>(parseBridgeAddress("npub1typo"))
        assertIs<BridgeAddress.Empty>(parseBridgeAddress(""))
    }
}
