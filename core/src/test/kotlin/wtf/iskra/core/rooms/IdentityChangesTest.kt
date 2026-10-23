package wtf.iskra.core.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uniffi.matrix_sdk_crypto.IdentityState

/** Чистая половина `IdentityChangesStore`: что баннер делает с сообщением SDK. */
class IdentityChangesTest {
    private fun change(user: String, kind: IdentityChange.Kind = IdentityChange.Kind.CHANGED) =
        IdentityChange(user, user.removePrefix("@"), kind)

    @Test
    fun `нарушение закрепления — «новые ключи», нарушение проверки — серьёзный случай`() {
        assertEquals(IdentityChange.Kind.CHANGED, IdentityChanges.kind(IdentityState.PIN_VIOLATION))
        assertEquals(IdentityChange.Kind.CHANGED_AFTER_VERIFICATION, IdentityChanges.kind(IdentityState.VERIFICATION_VIOLATION))
    }

    @Test
    fun `закреплён или проверен — предупреждения нет`() {
        assertNull(IdentityChanges.kind(IdentityState.PINNED))
        assertNull(IdentityChanges.kind(IdentityState.VERIFIED))
    }

    @Test
    fun `новый человек встаёт в конец, старые первыми`() {
        val list = IdentityChanges.with(IdentityChanges.with(emptyList(), change("@a:x")), change("@b:x"))
        assertEquals(listOf("@a:x", "@b:x"), list.map { it.userId })
    }

    @Test
    fun `повторное сообщение о том же человеке заменяет прежнее на его месте`() {
        val start = listOf(change("@a:x"), change("@b:x"))
        val list = IdentityChanges.with(start, change("@a:x", IdentityChange.Kind.CHANGED_AFTER_VERIFICATION))
        assertEquals(listOf("@a:x", "@b:x"), list.map { it.userId })
        assertEquals(IdentityChange.Kind.CHANGED_AFTER_VERIFICATION, list.first().kind)
    }

    @Test
    fun `снять — только его, остальные на местах`() {
        val start = listOf(change("@a:x"), change("@b:x"), change("@c:x"))
        assertEquals(listOf("@a:x", "@c:x"), IdentityChanges.without(start, "@b:x").map { it.userId })
    }
}
