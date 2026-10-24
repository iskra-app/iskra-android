package wtf.iskra.core.rooms

import wtf.iskra.core.support.ResourceStrings
import wtf.iskra.core.timeline.DayDivider
import wtf.iskra.core.timeline.DeliveryStatus
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.RoomEvent
import wtf.iskra.core.timeline.RoomEventKind
import wtf.iskra.core.timeline.TimelineRow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Что строка списка берёт у самого чата. Порт части `RoomPeekTests`: слова — те же, что на ленте. */
class PeekLineTest {
    private val strings = ResourceStrings()
    private val at = Instant.parse("2026-10-23T12:00:00Z")

    private fun message(
        kind: MessageKind,
        own: Boolean = false,
        delivery: DeliveryStatus? = null,
        name: String = "Аня",
        at: Instant = this.at,
    ) = TimelineRow.MessageRow(
        Message(id = "m$at", kind = kind, senderId = "@a:x", senderName = name, senderAvatarUrl = null, isOwn = own, timestamp = at, delivery = delivery),
    )

    @Test
    fun theLastWordsOfTheChatAreTheRow() {
        val line = PeekLine.last(listOf(message(MessageKind.Text("раз")), message(MessageKind.Text("два"))), strings)
        assertEquals("два", line?.text)
        assertEquals("Аня", line?.sender)
    }

    @Test
    fun aDeletedMessageSaysSoInsteadOfQuotingThePreviousOne() {
        val line = PeekLine.last(listOf(message(MessageKind.Text("привет")), message(MessageKind.Deleted)), strings)
        assertEquals("Message deleted", line?.text)
    }

    @Test
    fun aGreyLineIsTheLastThingThatHappened() {
        val event = TimelineRow.EventRow(RoomEvent("e", RoomEventKind.RoomRenamed("Новое"), at.plusSeconds(5)))
        val line = PeekLine.last(listOf(message(MessageKind.Text("привет")), event), strings)
        assertEquals(strings.format(wtf.iskra.core.R.string.room_event_room_renamed, "Новое"), line?.text)
        assertNull(line?.kind)
        assertNull(line?.mark)
    }

    @Test
    fun dividersAreSkipped() {
        val rows = listOf(message(MessageKind.Text("привет")), TimelineRow.DayDividerRow(DayDivider("d", at)), TimelineRow.UnreadDivider)
        assertEquals("привет", PeekLine.last(rows, strings)?.text)
    }

    @Test
    fun yourOwnMessageCarriesItsProgressAndNoSender() {
        val line = PeekLine.last(listOf(message(MessageKind.Text("ок"), own = true, delivery = DeliveryStatus.Read)), strings)
        assertEquals(LastMessageMark.READ, line?.mark)
        assertNull(line?.sender)
    }

    @Test
    fun aDeletedMessageOfYoursIsNotWaitedOn() {
        val line = PeekLine.last(listOf(message(MessageKind.Deleted, own = true, delivery = DeliveryStatus.Sent)), strings)
        assertNull(line?.mark)
    }

    @Test
    fun somethingWithNoWordsFallsBackToTheSdk() {
        assertNull(PeekLine.last(listOf(message(MessageKind.Text("раньше")), message(MessageKind.Unsupported)), strings))
        assertNull(PeekLine.last(emptyList(), strings))
    }

    @Test
    fun attachmentsReadAsTheirWordsAndCarryTheirIcon() {
        fun text(kind: MessageKind) = PeekLine.text((message(kind) as TimelineRow.MessageRow).message, strings)
        assertEquals(PreviewKind.LOCATION, PeekLine.previewKind(MessageKind.Location(1.0, 2.0, null)))
        assertEquals("Location", text(MessageKind.Location(1.0, 2.0, null)))
        assertEquals("Live location", text(MessageKind.Location(1.0, 2.0, null, MessageKind.Location.LiveSpan(true, at, null))))
        assertEquals("Когда?", text(MessageKind.Poll(wtf.iskra.core.timeline.Poll.of("Когда?", true, 1, emptyList(), emptyMap(), false, "@me:x"))))
        assertNull(PeekLine.previewKind(MessageKind.Text("слова")))
    }

    @Test
    fun aStateEventWithNoWordsOfItsOwnIsNotTheRow() {
        val hidden = TimelineRow.EventRow(RoomEvent("e1", RoomEventKind.Hidden("RoomPinnedEvents"), at.plusSeconds(5)))
        val line = PeekLine.last(listOf(message(MessageKind.Text("привет")), hidden), strings)
        assertEquals("привет", line?.text)
    }
}
