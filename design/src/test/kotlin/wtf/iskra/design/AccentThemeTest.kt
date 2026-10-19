package wtf.iskra.design

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccentThemeTest {
    @Test
    fun storedNamesAreTheIosOnes() {
        // Хранится по имени, как на iOS: перенос оформления не должен зависеть от порядка enum.
        assertEquals(
            listOf("system", "red", "orange", "yellow", "green", "cyan", "blue", "violet", "mono"),
            AccentTheme.entries.map { it.storageName },
        )
        assertEquals("appearance.accent", AccentTheme.STORAGE_KEY)
    }

    @Test
    fun defaultIsBlue() {
        assertEquals(AccentTheme.BLUE, AccentTheme.Default)
        assertEquals(AccentTheme.BLUE, AccentTheme.fromStorage(null, sdk = 36))
        assertEquals(AccentTheme.BLUE, AccentTheme.fromStorage("plaid", sdk = 36))
    }

    /** «Как в системе» есть только с API 31; сохранённый на другом устройстве читается как синий. */
    @Test
    fun systemAccentNeedsDynamicColour() {
        assertEquals(AccentTheme.SYSTEM, AccentTheme.fromStorage("system", sdk = 31))
        assertEquals(AccentTheme.BLUE, AccentTheme.fromStorage("system", sdk = 30))
        assertEquals(AccentTheme.SYSTEM, AccentTheme.choices(sdk = 31).first())
        assertFalse(AccentTheme.SYSTEM in AccentTheme.choices(sdk = 29))
    }

    /** На каждом акценте, в обеих темах, слова на акценте и в своём пузыре читаются (WCAG AA). */
    @Test
    fun everyAccentCarriesReadableWords() {
        for (theme in AccentTheme.entries) for (dark in listOf(false, true)) {
            val colors = theme.resolve(dark)
            assertTrue(contrast(colors.color, colors.foreground) >= 4.5, "$theme dark=$dark: слова на акценте")
            assertTrue(contrast(colors.bubble, colors.onBubble) >= 4.5, "$theme dark=$dark: свой пузырь")
            assertTrue(contrast(colors.incomingBubble, colors.onIncomingBubble) >= 4.5, "$theme dark=$dark: чужой пузырь")
        }
    }

    /** Моно — без цвета: схема серая, и свой пузырь — не чёрная плитка. */
    @Test
    fun monoHasNoHue() {
        val light = AccentTheme.MONO.resolve(dark = false)
        for (color in listOf(light.color, light.bubble, light.scheme.surface)) {
            assertEquals(color.red, color.green, 0.01f)
            assertEquals(color.green, color.blue, 0.01f)
        }
        assertNotEquals(light.color, light.bubble)
        assertEquals(1f, light.bubbleOnWallpaper.alpha, "на обоях пузырь непрозрачный")
    }

    /** Схема — из своего цвета: красный акцент и синий дают разные `primary`. */
    @Test
    fun accentsBuildTheirOwnScheme() {
        assertNotEquals(AccentTheme.RED.resolve(dark = false).color, AccentTheme.BLUE.resolve(dark = false).color)
        assertNotEquals(AccentTheme.BLUE.resolve(dark = false).scheme.surface, AccentTheme.BLUE.resolve(dark = true).scheme.surface)
    }

    /** Material You — вся динамическая схема, а не только акцент. */
    @Test
    fun systemTakesTheWholeDynamicScheme() {
        val dynamic = lightColorScheme(primary = Color(0xFF6750A4), surface = Color(0xFFFEF7FF))
        val colors = AccentTheme.SYSTEM.resolve(dark = false, system = dynamic)
        assertEquals(dynamic, colors.scheme)
        assertEquals(dynamic.primary, colors.color)
        // Без динамической схемы (ниже API 31) — синяя.
        assertEquals(AccentTheme.BLUE.resolve(dark = false).color, AccentTheme.SYSTEM.resolve(dark = false).color)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    @Test
    fun appearanceModeFollowsTheSystemOnlyWhenAskedTo() {
        assertTrue(AppearanceMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(AppearanceMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(AppearanceMode.DARK.isDark(systemIsDark = false))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStorage(null))
    }
}
