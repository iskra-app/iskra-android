package wtf.iskra.core.timeline

import wtf.iskra.core.R
import wtf.iskra.core.support.Strings
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проходы ленты над готовыми строками — порт `DayDividerTests`, `ReadReceiptTests`, `RoomEventTests`
 * и `UnreadDividerTests`. Настоящий элемент ленты SDK вне SDK не собрать, поэтому они тестируются
 * напрямую, как на iOS.
 */
class TimelineRowsTest {
    private val epoch = Instant.EPOCH

    private fun message(id: String, own: Boolean = false, delivery: DeliveryStatus? = if (own) DeliveryStatus.Sent else null, readAt: Instant? = null) =
        TimelineRow.MessageRow(
            Message(
                id = id, kind = MessageKind.Text("…"),
                senderId = if (own) "@me:example.org" else "@a:example.org", senderName = if (own) "Я" else "А",
                senderAvatarUrl = null, isOwn = own, timestamp = epoch, delivery = delivery, readAt = readAt,
            ),
        )

    private fun divider(id: String) = TimelineRow.DayDividerRow(DayDivider(id, epoch))
    private fun event(id: String) = TimelineRow.EventRow(RoomEvent(id, RoomEventKind.Joined(listOf(person("А"))), epoch))
    private fun person(name: String) = RoomEventPerson(name, name)

    private fun delivery(rows: List<TimelineRow>, id: String) = rows.firstNotNullOfOrNull { it.message?.takeIf { m -> m.id == id } }?.delivery
    private fun readAt(rows: List<TimelineRow>, id: String) = rows.firstNotNullOfOrNull { it.message?.takeIf { m -> m.id == id } }?.readAt
    private fun marked(rows: List<TimelineRow>) = rows.mapNotNull { it.message?.takeIf { m -> m.carriesReadMark }?.id }

    // MARK: Даты

    @Test
    fun aDayWithNothingUnderItLosesItsHeader() {
        assertEquals(listOf("today", "m"), TimelineRows.withoutEmptyDays(listOf(divider("yesterday"), divider("today"), message("m"))).map { it.id })
    }

    @Test
    fun aDayWithMessagesOrAGreyLineKeepsItsHeader() {
        assertEquals(listOf("d", "m1", "m2"), TimelineRows.withoutEmptyDays(listOf(divider("d"), message("m1"), message("m2"))).map { it.id })
        assertEquals(listOf("d", "e"), TimelineRows.withoutEmptyDays(listOf(divider("d"), event("e"))).map { it.id })
    }

    @Test
    fun onlyTheLivingDaySurvivesARunOfEmptyOnes() {
        val rows = TimelineRows.withoutEmptyDays(listOf(divider("mon"), divider("tue"), divider("wed"), message("m"), divider("thu")))
        assertEquals(listOf("wed", "m"), rows.map { it.id })
        assertTrue(TimelineRows.withoutEmptyDays(listOf(divider("d"))).isEmpty())
    }

    // MARK: Квитанции

    @Test
    fun aReceiptAtTheBottomReadsEverythingAboveIt() {
        val rows = TimelineRows.withReadPropagated(
            listOf(message("m1", own = true), message("m2", own = true), message("m3", own = true, delivery = DeliveryStatus.Read)),
        )
        assertEquals(DeliveryStatus.Read, delivery(rows, "m1"))
        assertEquals(DeliveryStatus.Read, delivery(rows, "m2"))
        assertEquals(listOf("m3"), marked(rows))
    }

    @Test
    fun messagesBelowTheReceiptStaySentAndUnsentAreLeftAlone() {
        val rows = TimelineRows.withReadPropagated(
            listOf(
                message("sending", own = true, delivery = DeliveryStatus.Sending),
                message("failed", own = true, delivery = DeliveryStatus.Failed(SendFailure.SERVER)),
                message("read", own = true, delivery = DeliveryStatus.Read),
                message("new", own = true),
            ),
        )
        assertEquals(DeliveryStatus.Sending, delivery(rows, "sending"))
        assertEquals(DeliveryStatus.Failed(SendFailure.SERVER), delivery(rows, "failed"))
        assertEquals(DeliveryStatus.Sent, delivery(rows, "new"))
        assertEquals(listOf("read"), marked(rows))
    }

    @Test
    fun aReceiptOnTheirMessageReadsYoursAboveIt() {
        val rows = TimelineRows.withReadPropagated(listOf(message("old", own = true), message("theirs"), message("new", own = true)), readUpTo = 2)
        assertEquals(DeliveryStatus.Read, delivery(rows, "old"))
        assertEquals(DeliveryStatus.Sent, delivery(rows, "new"))
        assertNull(delivery(rows, "theirs"))
    }

    @Test
    fun theNearestReceiptIsTheOneThatAnswers() {
        val earlier = Instant.ofEpochSecond(1_700_000_000)
        val later = Instant.ofEpochSecond(1_700_003_600)
        val rows = TimelineRows.withReadPropagated(
            listOf(
                message("old", own = true),
                message("mid", own = true, delivery = DeliveryStatus.Read, readAt = earlier),
                message("new", own = true),
                message("theirs"),
            ),
            readUpTo = 4, at = later,
        )
        assertEquals(later, readAt(rows, "new"))
        assertEquals(earlier, readAt(rows, "mid"))
        assertEquals(earlier, readAt(rows, "old"))
        assertEquals(listOf("new"), marked(rows))
    }

    @Test
    fun noMomentAnywhereInventsNone() {
        val rows = TimelineRows.withReadPropagated(listOf(message("m1", own = true), message("m2", own = true, delivery = DeliveryStatus.Read)))
        assertEquals(DeliveryStatus.Read, delivery(rows, "m1"))
        assertNull(readAt(rows, "m1"))
    }

    // MARK: Полоса непрочитанного

    @Test
    fun theBarStaysOnlyAboveSomethingSomebodyElseSaid() {
        assertEquals(
            listOf("a", TimelineRow.UNREAD_DIVIDER_ID, "b"),
            TimelineRows.withUnreadDividerIfEarned(listOf(message("a"), TimelineRow.UnreadDivider, message("b"))).map { it.id },
        )
        assertEquals(listOf("a"), TimelineRows.withUnreadDividerIfEarned(listOf(message("a"), TimelineRow.UnreadDivider)).map { it.id })
        assertFalse(
            TimelineRow.UnreadDivider in TimelineRows.withUnreadDividerIfEarned(
                listOf(message("a"), TimelineRow.UnreadDivider, event("joined"), message("mine", own = true)),
            ),
        )
        assertEquals(
            TimelineRow.UnreadDivider,
            TimelineRows.withUnreadDividerIfEarned(listOf(TimelineRow.UnreadDivider, message("mine", own = true), message("theirs"))).first(),
        )
    }

    @Test
    fun aDateThatOnlyIntroducedAnUnearnedBarGoesWithIt() {
        val rows = TimelineRows.withoutEmptyDays(TimelineRows.withUnreadDividerIfEarned(listOf(message("a"), divider("today"), TimelineRow.UnreadDivider)))
        assertEquals(listOf("a"), rows.map { it.id })
    }

    // MARK: Служебные строки

    @Test
    fun consecutiveJoinsPoolIntoOneLineWithoutRepeatingAPerson() {
        assertEquals(
            RoomEventKind.Joined(listOf(person("Аня"), person("Борис"))),
            RoomEventKind.Joined(listOf(person("Аня"))).merged(RoomEventKind.Joined(listOf(person("Аня"), person("Борис")))),
        )
        assertNull(RoomEventKind.Joined(listOf(person("Аня"))).merged(RoomEventKind.Left(listOf(person("Борис")))))
        assertNull(RoomEventKind.RoomCreated.merged(RoomEventKind.RoomCreated))
    }

    @Test
    fun peopleAreNamedUpToTwoAndCountedAfterThat() {
        val strings = object : Strings {
            override fun get(id: Int) = when (id) {
                R.string.room_event_joined -> "%s joined"
                R.string.room_event_pair -> "%1\$s and %2\$s"
                else -> error("unexpected $id")
            }
            override fun plural(id: Int, count: Int, vararg args: Any) = "%1\$s and %2\$d others".format(*args)
        }
        fun line(vararg names: String) = RoomEvent("1", RoomEventKind.Joined(names.map(::person)), epoch).text(strings)
        assertEquals("Аня joined", line("Аня"))
        assertEquals("Аня and Борис joined", line("Аня", "Борис"))
        assertEquals("Аня and 2 others joined", line("Аня", "Борис", "Витя"))
    }

    // MARK: Заходы

    @Test
    fun aRunOfOnePersonsMessagesSharesOneFaceAtItsFoot() {
        val at = Instant.ofEpochSecond(1_000)
        fun said(id: String, sender: String, seconds: Long) = Mapped(
            Mapped.Body.MessageBody(
                Message(id, MessageKind.Text(id), sender, sender, null, false, at.plusSeconds(seconds), null),
            ),
        )
        val rows = TimelineRows.rows(listOf(said("a", "@a", 0), said("b", "@a", 60), said("c", "@b", 70), said("d", "@b", 1000)))
        val messages = rows.mapNotNull { it.message }
        assertEquals(listOf(false, true, false, false), messages.map { it.isContinuation })
        assertEquals(listOf(true, false, false, false), messages.map { it.isContinued })
    }
}
