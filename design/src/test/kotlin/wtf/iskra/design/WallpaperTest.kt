package wtf.iskra.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Что за разговором и светлы ли должны быть слова на нём. Порт `WallpaperTests`. */
class WallpaperTest {
    @Test
    fun storageRoundTripsAndUnknownIsTheDefault() {
        Wallpaper.entries.forEach { assertEquals(it, Wallpaper.fromStorage(it.storageName)) }
        assertEquals(Wallpaper.NONE, Wallpaper.fromStorage(null))
        assertEquals(Wallpaper.NONE, Wallpaper.fromStorage("neon"))
    }

    @Test
    fun onlyGradientsHaveAKnownBrightness() {
        assertNull(Wallpaper.NONE.luminance)
        assertNull(Wallpaper.PHOTO.luminance)
        listOf(Wallpaper.SUNSET, Wallpaper.SEA, Wallpaper.FOREST, Wallpaper.DUSK, Wallpaper.SAND, Wallpaper.NIGHT).forEach {
            assertNotNull(it.luminance)
        }
        assertTrue(Wallpaper.SAND.luminance!! > Wallpaper.NIGHT.luminance!!)
    }

    @Test
    fun luminanceOfBlackAndWhite() {
        assertEquals(0f, Wallpaper.luminance(0f, 0f, 0f), 0.0001f)
        assertEquals(1f, Wallpaper.luminance(1f, 1f, 1f), 0.0001f)
    }

    @Test
    fun wordsGoLightOnADarkPictureAndTheVeilTakesItFurther() {
        assertTrue(Wallpaper.isDark(0.05f, 0f))
        assertFalse(Wallpaper.isDark(0.5f, 0f))
        // Яркая картинка под тёмной вуалью темнеет настолько, что читается белое.
        assertFalse(Wallpaper.isDark(0.3f, 0f))
        assertTrue(Wallpaper.isDark(0.3f, Wallpaper.MAXIMUM_DIMMING))
        // Светлая вуаль, наоборот, бледнит: слова тёмные.
        assertFalse(Wallpaper.isDark(0.1f, Wallpaper.MAXIMUM_DIMMING, lightens = true))
    }

    @Test
    fun noWallpaperIsNotSet() {
        assertFalse(Wallpaper.NONE.isSet)
        assertTrue(Wallpaper.PHOTO.isSet)
    }
}
