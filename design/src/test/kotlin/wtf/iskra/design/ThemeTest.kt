package wtf.iskra.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сетка из четырёх dp держится тестом, а не вниманием (docs/PROMPT.md, 6.2).
 *
 * Перебираются *все* размеры в `Theme.Spacing`, `Theme.Radius` и `Theme.Size`, а не список, который
 * кто-то не забудет пополнить: новое число, не кратное 4, падает здесь. Исключения названы по
 * именам и объяснены в заголовке `Theme.kt` — как в iOS.
 */
class ThemeTest {
    private val offGrid = setOf(
        "Spacing.withinRun", "Spacing.albumGap", "Spacing.groupGap", "Size.recordingDot", "Size.unreadDot",
        "Size.menuIcon", "Size.waveformBar", "Size.waveformBarMinimum", "Border.hairline",
        "Border.thick",
    )

    /** `val x = 12.dp` в object компилируется в статическое поле float; `const` — заглавными. */
    private fun dimensions(holder: Any): Map<String, Float> =
        holder.javaClass.declaredFields
            .filter { it.type == Float::class.javaPrimitiveType && it.name != it.name.uppercase() }
            .associate { field ->
                field.isAccessible = true
                "${holder.javaClass.simpleName}.${field.name}" to field.getFloat(null)
            }

    @Test
    fun everySizeIsOnTheGridExceptTheNamedOnes() {
        val all = dimensions(Theme.Spacing) + dimensions(Theme.Radius) + dimensions(Theme.Size) +
            dimensions(Theme.Border)
        assertTrue(all.size > 80, "перебор полей ничего не нашёл — поменялось устройство Dp?")
        val stray = all.filter { (name, value) -> name !in offGrid && value % 4f != 0f }
        assertEquals(emptyMap(), stray, "размер вне сетки 4 dp; если это решение — добавить в offGrid и в Theme.kt")
    }

    @Test
    fun theNumbersMatchIos() {
        // Примеры из docs/PROMPT.md, 6.2: если они разошлись, разошлось всё.
        assertEquals(24f, Theme.Radius.bubble.value)
        assertEquals(20f, Theme.Radius.picture.value)
        assertEquals(16f, Theme.Radius.card.value)
        assertEquals(12f, Theme.Radius.control.value)
        assertEquals(listOf(4f, 8f, 12f, 16f, 24f), with(Theme.Spacing) { listOf(tight, close, normal, roomy, generous) }.map { it.value })
        assertEquals(12f, Theme.Spacing.betweenSenders.value)
        assertEquals(2f, Theme.Spacing.withinRun.value)
        assertEquals(40f, Theme.Size.avatar.value)
        assertEquals(56f, Theme.Size.roomAvatar.value)
        assertEquals(64f, Theme.Size.avatarLarge.value)
        assertEquals(20f, Theme.Size.unreadBadge.value)
        assertEquals(360f, Theme.Size.emojiPanelWidth.value)
    }

    @Test
    fun storageBandsWrapRatherThanRunOut() {
        assertEquals(Theme.Storage.band(0), Theme.Storage.band(Theme.Storage.bands.size))
    }
}
