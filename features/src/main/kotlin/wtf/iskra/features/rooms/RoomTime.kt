package wtf.iskra.features.rooms

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Время в строке списка, как у мессенджеров: сегодня — часы, на этой неделе — день, в этом году —
 * число и месяц, раньше — полная дата. Чистая функция: часы и пояс передаются, чтобы правило можно
 * было закрепить тестом.
 */
object RoomTime {
    fun label(
        at: Instant,
        now: Instant,
        zone: ZoneId,
        locale: Locale,
        /** Шаблон «число и месяц» для языка; на устройстве — от Android, в тесте — свой. */
        monthDayPattern: (Locale) -> String = ::systemMonthDay,
    ): String {
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when {
            day == today || day.isAfter(today) ->
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(at.atZone(zone))
            day.isAfter(today.minusDays(WEEK)) ->
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            day.year == today.year -> DateTimeFormatter.ofPattern(monthDayPattern(locale), locale).format(day)
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(day)
        }
    }

    /** «12 окт.», «Oct 12» — число и месяц в порядке языка, без года. */
    private fun systemMonthDay(locale: Locale): String =
        android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM")

    /** Шесть дней назад — ещё «эта неделя»: седьмой был бы тем же днём недели, что сегодня. */
    private const val WEEK = 7L
}
