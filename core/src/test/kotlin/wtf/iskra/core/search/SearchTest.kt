package wtf.iskra.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeContent
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.TimelineItemContent
import wtf.iskra.core.support.Strings

/**
 * Что поиск вправе вернуть (порт `SearchTests.swift`). Слова удалённого сообщения могут пережить само
 * сообщение в индексе; не пускает их на экран только `excerpt`.
 */
class SearchTest {
    private val strings = object : Strings {
        override fun get(id: Int) = "#$id"
        override fun format(id: Int, vararg args: Any) = "#$id"
        override fun plural(id: Int, count: Int, vararg args: Any) = "#$id"
    }

    private fun content(kind: MsgLikeKind) =
        TimelineItemContent.MsgLike(MsgLikeContent(kind, emptyList(), null, null, null))

    private fun message(body: String) =
        MsgLikeKind.Message(MessageContent(MessageType.Text(TextMessageContent(body, null)), body, false, null))

    @Test
    fun aLiveMessageIsAHitInItsOwnWords() {
        assertEquals("ключи под ковриком", SearchStore.excerpt(content(message("ключи под ковриком")), strings))
    }

    @Test
    fun aDeletedMessageIsNeverAHit() {
        assertNull(SearchStore.excerpt(content(MsgLikeKind.Redacted), strings))
    }

    @Test
    fun anUnreadableMessageIsNeverAHit() {
        assertNull(SearchStore.excerpt(content(MsgLikeKind.UnableToDecrypt(org.matrix.rustcomponents.sdk.EncryptedMessage.Unknown)), strings))
    }
}
