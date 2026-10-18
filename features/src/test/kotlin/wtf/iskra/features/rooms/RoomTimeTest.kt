package wtf.iskra.features.rooms

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Время в строке списка: часы, день недели, число, дата. */
class RoomTimeTest {
    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 10, 18, 15, 0, 0, 0, zone).toInstant()
    private fun at(month: Int, day: Int, hour: Int = 9, year: Int = 2026) =
        ZonedDateTime.of(year, month, day, hour, 30, 0, 0, zone).toInstant()

    private fun label(at: java.time.Instant, locale: Locale = Locale.forLanguageTag("ru")) =
        RoomTime.label(at, now, zone, locale) { "d MMM" }

    @Test
    fun todayIsAClockTime() {
        assertEquals("09:30", label(at(10, 18)))
        assertEquals("9:30 AM", label(at(10, 18), Locale.US).replace("\u202F", " "))
    }

    @Test
    fun thisWeekIsTheDay() {
        assertEquals("пт", label(at(10, 16)).lowercase().trimEnd('.'))
        // Шесть дней назад — ещё неделя; семь — уже число.
        assertEquals("пн", label(at(10, 12)).lowercase().trimEnd('.'))
        assertEquals("11 окт.", label(at(10, 11)))
    }

    @Test
    fun earlierThisYearIsDayAndMonth() {
        assertEquals("3 мар.", label(at(3, 3)))
    }

    @Test
    fun anotherYearIsAFullDate() {
        assertEquals("03.03.2025", label(at(3, 3, year = 2025)))
    }
}
