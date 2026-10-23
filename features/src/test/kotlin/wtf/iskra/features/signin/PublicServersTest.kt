package wtf.iskra.features.signin

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Список серверов для того, у кого нет своего адреса. Порт проверок `PublicServer`. */
class PublicServersTest {
    @Test
    fun theListIsShortAndMatrixOrgIsFirst() {
        val servers = PublicServer.all(isRussian = false)
        assertEquals(listOf("matrix.org", "tchncs.de", "quassel.io", "nope.chat"), servers.map { it.address })
    }

    @Test
    fun onlyARussianPhoneIsToldMatrixOrgMayNotAnswer() {
        assertNotNull(PublicServer.all(isRussian = true).first().warning)
        assertNull(PublicServer.all(isRussian = false).first().warning)
        assertTrue(PublicServer.all(isRussian = true).drop(1).all { it.warning == null })
    }

    @Test
    fun russianIsALanguageOrARegion() {
        assertTrue(PublicServer.isRussian(listOf(Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("ru-KZ"))))
        assertTrue(PublicServer.isRussian(listOf(Locale.forLanguageTag("en-RU"))))
        assertEquals(false, PublicServer.isRussian(listOf(Locale.forLanguageTag("en-GB"), Locale.forLanguageTag("de-DE"))))
        assertEquals(false, PublicServer.isRussian(emptyList()))
    }

    @Test
    fun theGuideIsOurOwn() {
        assertEquals("https://iskra.wtf/server.html", PublicServer.OWN_SERVER_GUIDE)
    }
}
