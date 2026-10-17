package moscow.swag.iskra.design

import androidx.compose.ui.graphics.Color
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

    @Test
    fun yellowCarriesBlackWords() {
        assertEquals(Color.Black, AccentTheme.YELLOW.resolve(dark = false).foreground)
        assertEquals(Color.White, AccentTheme.RED.resolve(dark = false).foreground)
    }

    /** Моно: на акценте только цвет фона, и пузырь — серый, а не чёрная плитка. */
    @Test
    fun monoIsTheBlackWhitePair() {
        val light = AccentTheme.MONO.resolve(dark = false)
        val dark = AccentTheme.MONO.resolve(dark = true)
        assertEquals(Color.Black, light.color)
        assertEquals(Color.White, light.foreground)
        assertEquals(Color.White, dark.color)
        assertEquals(Color.Black, dark.foreground)
        assertNotEquals(light.color, light.bubble)
        assertTrue(light.bubble.alpha < 1f)
        assertEquals(1f, light.bubbleOnWallpaper.alpha, "на обоях пузырь непрозрачный")
        assertNotEquals(light.color, light.control, "белая дорожка под белой ручкой — переключатель без состояния")
        assertEquals(Theme.Tint.MONO, light.washOpacity)
    }

    /** Material You меняет акцент и то, что на нём, — и ничего больше. */
    @Test
    fun systemTakesPrimaryAndOnPrimary() {
        val primary = Color(0xFF6750A4)
        val onPrimary = Color(0xFFFFFFFF)
        val colors = AccentTheme.SYSTEM.resolve(dark = false, systemPrimary = primary, systemOnPrimary = onPrimary)
        assertEquals(primary, colors.color)
        assertEquals(onPrimary, colors.foreground)
        assertEquals(primary, colors.bubble)
    }

    @Test
    fun appearanceModeFollowsTheSystemOnlyWhenAskedTo() {
        assertTrue(AppearanceMode.SYSTEM.isDark(systemIsDark = true))
        assertFalse(AppearanceMode.LIGHT.isDark(systemIsDark = true))
        assertTrue(AppearanceMode.DARK.isDark(systemIsDark = false))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromStorage(null))
    }
}
