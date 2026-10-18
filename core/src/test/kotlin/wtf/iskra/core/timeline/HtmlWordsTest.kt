package wtf.iskra.core.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Слова сообщения без разметки — то, что строка списка показывает вместо `**важно**`. */
class HtmlWordsTest {
    @Test
    fun formattingIsDroppedAndWordsKept() {
        assertEquals("это важно", HtmlWords.plain("это <strong>важно</strong>"))
    }

    /** Цитата ответа нужна старым клиентам; здесь она нарисовала бы вопрос дважды. */
    @Test
    fun theReplyFallbackIsThrownAwayWhole() {
        val html = "<mx-reply><blockquote><a href=\"https://matrix.to/#/x\">In reply to</a> вопрос</blockquote></mx-reply>ответ"
        assertEquals("ответ", HtmlWords.plain(html))
    }

    @Test
    fun entitiesAreDecoded() {
        assertEquals("a < b & \"c\" ' é 😀", HtmlWords.plain("a &lt; b &amp; &quot;c&quot; &#39; &#xE9; &#128512;"))
    }

    @Test
    fun linesBecomeSpaces() {
        assertEquals("раз два три", HtmlWords.plain("<p>раз</p><p>два<br/>три</p>"))
    }

    @Test
    fun noWordsMeansTheBodyIsUsed() {
        assertNull(HtmlWords.plain("<p> </p>"))
        assertEquals("**важно**", HtmlWords.words("**важно**", "<p></p>"))
        assertEquals("важно", HtmlWords.words("**важно**", "<b>важно</b>"))
        assertEquals("тело", HtmlWords.words("тело", null))
    }

    @Test
    fun anUnknownEntityIsLeftAsWritten() {
        assertEquals("&nosuch;", HtmlWords.plain("&nosuch;"))
    }
}
