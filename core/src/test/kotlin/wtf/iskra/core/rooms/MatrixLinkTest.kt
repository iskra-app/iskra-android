package wtf.iskra.core.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Что лежит в QR-коде — и всё, что мог написать чужой клиент. Тесты iOS. */
class MatrixLinkTest {
    private fun parse(text: String) = MatrixLink.parse(text)

    @Test
    fun aPersonsLinkIsOurPageAndReadsBackAsThePerson() {
        val link = MatrixLink.User("@boris:matrix.org")
        assertEquals("https://s.iskra.wtf/@boris:matrix.org", link.url)
        assertEquals(link, parse(link.url))
    }

    @Test
    fun aRoomAliasIsEscapedInThePathAndItsServersRideAlong() {
        val alias = MatrixLink.Room("#iskra:matrix.org")
        assertEquals("https://s.iskra.wtf/%23iskra:matrix.org", alias.url)
        assertEquals(alias, parse(alias.url))

        val id = MatrixLink.Room("!abc:matrix.org", listOf("matrix.org", "example.com"))
        assertEquals("https://s.iskra.wtf/!abc:matrix.org?via=matrix.org&via=example.com", id.url)
        assertEquals(id, parse(id.url))
    }

    @Test
    fun theLinkIntoTheAppNamesTheSigilTheWayMatrixDoesAndReadsBack() {
        val links = listOf(
            MatrixLink.User("@boris:matrix.org") to "wtf.iskra://u/boris:matrix.org",
            MatrixLink.Room("#iskra:matrix.org") to "wtf.iskra://r/iskra:matrix.org",
            MatrixLink.Room("!abc:matrix.org", listOf("matrix.org", "example.com")) to
                "wtf.iskra://roomid/abc:matrix.org?via=matrix.org&via=example.com",
        )
        for ((link, text) in links) {
            assertEquals(text, link.deepLink)
            assertEquals(link, parse(text))
        }
    }

    @Test
    fun theAppSchemeWithAHostIsAPersonOrARoomPercentEscapesAndAll() {
        assertEquals(MatrixLink.User("@anya:matrix.org"), parse("wtf.iskra://u/anya:matrix.org"))
        assertEquals(MatrixLink.User("@anya:matrix.org"), parse("wtf.iskra://u/anya%3Amatrix.org"))
        assertEquals(MatrixLink.Room("#iskra:matrix.org", listOf("example.com")), parse("wtf.iskra://r/iskra:matrix.org?via=example.com"))
        assertEquals(
            MatrixLink.Room("!abc:matrix.org", listOf("matrix.org", "example.com")),
            parse("wtf.iskra://roomid/abc:matrix.org?via=matrix.org&via=example.com"),
        )
        assertEquals(MatrixLink.Room("!abc:matrix.org", listOf("ex.ample.com")), parse("wtf.iskra://roomid/abc:matrix.org?via=ex%2Eample.com"))
    }

    @Test
    fun theAppsOwnHostlessUrlsAreNotLinksToAnybody() {
        assertNull(parse("wtf.iskra:/open?room=!abc:matrix.org"))
        assertNull(parse("wtf.iskra:/oauth-callback?code=secret&state=x"))
        assertNull(parse("wtf.iskra:/share"))
        assertNull(parse("wtf.iskra://open?room=!abc:matrix.org"))
        assertNull(parse("wtf.iskra://x/boris:matrix.org"))
        assertNull(parse("wtf.iskra://u/"))
    }

    @Test
    fun ourPageIsReadInThePathAndInTheFragmentAsMatrixToIs() {
        assertEquals(MatrixLink.User("@anya:matrix.org"), parse("https://s.iskra.wtf/@anya:matrix.org"))
        assertEquals(MatrixLink.Room("#room:server.org"), parse("https://s.iskra.wtf/%23room:server.org"))
        assertEquals(MatrixLink.Room("!id:server.org", listOf("x.org", "y.org")), parse("https://s.iskra.wtf/!id:server.org?via=x.org&via=y.org"))
        assertEquals(MatrixLink.Room("!id:server.org", listOf("x.org")), parse("https://s.iskra.wtf/!id:server.org/\$event?via=x.org"))
        assertEquals(MatrixLink.Room("#room:server.org"), parse("https://s.iskra.wtf/#room:server.org"))
        assertEquals(MatrixLink.User("@anya:matrix.org"), parse("https://s.iskra.wtf/#/@anya:matrix.org"))
        assertEquals(MatrixLink.Room("#room:server.org"), parse("https://s.iskra.wtf/#/%23room:server.org"))
        assertEquals(MatrixLink.Room("!id:server.org", listOf("x.org")), parse("https://s.iskra.wtf/#/!id:server.org?via=x.org"))
        assertNull(parse("https://s.iskra.wtf/"))
        assertNull(parse("https://s.iskra.wtf/about"))
    }

    @Test
    fun everySpellingAnotherAppMightHaveUsedIsRead() {
        assertEquals(MatrixLink.User("@boris:matrix.org"), parse("https://matrix.to/#/@boris:matrix.org"))
        assertEquals(MatrixLink.Room("#iskra:matrix.org"), parse("https://matrix.to/#/%23iskra:matrix.org"))
        assertEquals(MatrixLink.Room("#iskra:matrix.org"), parse("https://matrix.to/#/#iskra:matrix.org"))
        assertEquals(MatrixLink.Room("!abc:matrix.org", listOf("matrix.org")), parse("https://matrix.to/#/!abc:matrix.org/\$event?via=matrix.org"))
        assertEquals(MatrixLink.User("@boris:matrix.org"), parse("matrix:u/boris:matrix.org"))
        assertEquals(MatrixLink.User("@boris:matrix.org"), parse("matrix:u/boris:matrix.org?action=chat"))
        assertEquals(MatrixLink.Room("#iskra:matrix.org"), parse("matrix:r/iskra:matrix.org"))
        assertEquals(MatrixLink.Room("!abc:matrix.org", listOf("example.com")), parse("matrix:roomid/abc:matrix.org?via=example.com"))
        assertEquals(MatrixLink.User("@boris:matrix.org"), parse(" @boris:matrix.org\n"))
        assertEquals(MatrixLink.Room("#iskra:matrix.org"), parse("#iskra:matrix.org"))
    }

    @Test
    fun anythingThatIsNotAPersonOrARoomIsNotALink() {
        assertNull(parse("https://example.com/#/@boris:matrix.org"))
        assertNull(parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(parse("https://matrix.to/"))
        assertNull(parse("matrix:x/boris:matrix.org"))
        assertNull(parse("boris"))
        assertNull(parse(""))
    }
}
