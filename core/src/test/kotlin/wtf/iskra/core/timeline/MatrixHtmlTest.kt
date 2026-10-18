package wtf.iskra.core.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Порт `MatrixHTMLTests`: что парсер делает с разметкой и что — с тем, что отправитель может в него бросить. */
class MatrixHtmlTest {
    private fun text(html: String) = MatrixHtml.parse(html)?.text

    private fun style(word: String, rich: RichText): RichStyle? {
        val start = rich.text.indexOf(word)
        if (start < 0) return null
        return rich.spans.lastOrNull { it.start <= start && start + word.length <= it.end }?.style
    }

    @Test
    fun inlineStylesAndNestedOnesCombined() {
        val rich = MatrixHtml.parse("<b>bold</b> <em>it</em> <del>gone</del> <code>x()</code> <strong><i>both</i></strong>")!!
        assertEquals("bold it gone x() both", rich.text)
        assertEquals(RichStyle(bold = true), style("bold", rich))
        assertEquals(RichStyle(italic = true), style("it ", rich.copy(text = rich.text)) ?: style("it", rich))
        assertEquals(RichStyle(strikethrough = true), style("gone", rich))
        assertEquals(RichStyle(code = true), style("x()", rich))
        assertEquals(RichStyle(bold = true, italic = true), style("both", rich))
    }

    @Test
    fun aLinkKeepsItsAddressAndAForbiddenSchemeIsWords() {
        val rich = MatrixHtml.parse("""<a href="https://iskra.chat">site</a> <a href="javascript:alert(1)">bad</a>""")!!
        assertEquals("https://iskra.chat", style("site", rich)?.link)
        assertNull(style("bad", rich)?.link)
    }

    @Test
    fun aMatrixToPillIsAMentionAndKnowsWhenItIsYou() {
        val html = """<a href="https://matrix.to/#/%40alice%3Amatrix.org">Alice</a> and <a href="https://matrix.to/#/@me:x.org">Me</a>"""
        val rich = MatrixHtml.parse(html, ownUserId = "@me:x.org")!!
        assertEquals("@alice:matrix.org", style("Alice", rich)?.mentionUserId)
        assertEquals(false, style("Alice", rich)?.mentionIsSelf)
        assertNull(style("Alice", rich)?.link)
        assertEquals(true, style("Me", rich)?.mentionIsSelf)
    }

    @Test
    fun theReplyFallbackIsDroppedWhole() {
        assertEquals("new answer", text("<mx-reply><blockquote><a href=\"https://matrix.to/#/!r/\$e\">In reply to</a> <b>old</b></blockquote></mx-reply>new answer"))
    }

    @Test
    fun paragraphsBreaksAndListsBecomeLines() {
        assertEquals("one\n\ntwo", text("<p>one</p>\n<p>two</p>"))
        assertEquals("a\nb\nc", text("a<br>b<br/>c"))
        assertEquals("• milk\n• bread", text("<ul>\n<li>milk</li>\n<li>bread</li>\n</ul>"))
        assertEquals("3. c\n4. d", text("<ol start=\"3\"><li>c</li><li><p>d</p></li></ol>"))
        assertEquals("before\n\n• x\n\nafter", text("<p>before</p><ul><li>x</li></ul><p>after</p>"))
    }

    @Test
    fun aQuotesLinesCarryABarAndTheQuoteStyle() {
        val rich = MatrixHtml.parse("<blockquote>wise words</blockquote>said")!!
        assertEquals("▍wise words\n\nsaid", rich.text)
        assertEquals(true, style("wise", rich)?.quote)
    }

    @Test
    fun preKeepsItsWhitespaceAndElsewhereItCollapses() {
        assertEquals("let x = 1\n    y", text("<pre><code>let x = 1\n    y</code></pre>"))
        assertEquals("lots of spaces", text("lots   of\n\n spaces"))
    }

    @Test
    fun entitiesUnknownTagsAndImages() {
        assertEquals("a & b <c> 😀 A", text("a &amp; b &lt;c&gt; &#128512; &#x41;"))
        assertEquals("still here", text("<marquee>still here</marquee>"))
        assertEquals("hi :wave:", text("""hi <img src="mxc://x/y" alt=":wave:">"""))
    }

    @Test
    fun spoilersAndUnderlineAreMarked() {
        val rich = MatrixHtml.parse("<span data-mx-spoiler>secret</span> <u>under</u> <span data-mx-spoiler=\"why\">two</span>")!!
        assertEquals(0, style("secret", rich)?.spoiler)
        assertEquals(1, style("two", rich)?.spoiler)
        assertEquals(true, style("under", rich)?.underline)
    }

    @Test
    fun aMisnestedTagCannotLeaveBoldOn() {
        val rich = MatrixHtml.parse("<b>bold <i>both</b> plain")!!
        assertNull(style("plain", rich))
    }

    @Test
    fun htmlWithNoWordsGivesWayToThePlainBody() {
        assertNull(MatrixHtml.parse("<p> </p><br>"))
        assertEquals("plain words", RichText.of("plain words", "<p></p>").text)
    }

    @Test
    fun bareAddressesStillBecomeLinks() {
        val rich = RichText.of("", "<b>see</b> https://example.com now")
        assertEquals("https://example.com", style("https://example.com", rich)?.link)
        val plain = RichText.of("look: https://matrix.org.", null)
        assertEquals("https://matrix.org", style("https://matrix.org", plain)?.link)
    }

    @Test
    fun whatASenderCanThrowAtIt() {
        assertTrue(text("<ol start=\"9223372036854775807\"><li>a</li><li>b</li></ol>") != null)
        assertEquals("0. a", text("<ol start=\"-5\"><li>a</li></ol>"))

        val started = System.nanoTime()
        assertTrue(text("<".repeat(60_000) + " end")!!.endsWith("end"))
        assertEquals("deep", text("<b>".repeat(5_000) + "deep" + "</b>".repeat(5_000)))
        assertTrue(text("<p>x</p>".repeat(4_000))!!.startsWith("x\n\nx"))
        assertTrue(System.nanoTime() - started < 6_000_000_000L)
    }
}
