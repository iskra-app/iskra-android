package wtf.iskra.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Порт `PendingSendsTests.swift`: сообщение встаёт на экран в миг отправки и уходит, когда приходит
 * эхо SDK. Сверять их не по чему, кроме слов, — здесь правила этой догадки и то, в какую сторону ей
 * позволено ошибаться.
 */
class PendingSendsTest {
    private val me = "@me:iskra.chat"
    private val now: Instant = Instant.now()

    private fun mine(text: String, at: Instant) = TimelineRow.MessageRow(
        Message("\$$text-${at.toEpochMilli()}", MessageKind.Text(text), me, "Я", null, true, at, DeliveryStatus.Sent),
    )

    private fun theirs(text: String, at: Instant) = TimelineRow.MessageRow(
        Message("\$them-$text", MessageKind.Text(text), "@them:iskra.chat", "Они", null, false, at, null),
    )

    @Test
    fun aMessageWithNoEchoYetStaysOnTheScreen() {
        val pending = PendingSends()
        pending.add("привет", null)

        pending.pruneEchoed(listOf(mine("что-то ещё", now)))

        assertEquals(listOf("привет"), pending.all.map { it.text })
    }

    @Test
    fun theEchoTakesItsPlace() {
        val pending = PendingSends()
        pending.add("привет", null)

        pending.pruneEchoed(listOf(mine("привет", now)))

        assertTrue(pending.isEmpty)
    }

    @Test
    fun aWordSaidAgainIsNotRetiredAgainstTheFirstTime() {
        val earlier = mine("ок", now.minusSeconds(30))
        val pending = PendingSends()
        pending.add("ок", null, alreadyShown = setOf(earlier.id))

        pending.pruneEchoed(listOf(earlier))
        assertEquals(1, pending.all.size)

        val echo = mine("ок", now)
        val taken = pending.pruneEchoed(listOf(earlier, echo))
        assertTrue(pending.isEmpty)
        assertEquals(setOf(echo.id), taken.keys)
    }

    @Test
    fun theSameWordsFromSomebodyElseAreNotOurEcho() {
        val pending = PendingSends()
        pending.add("привет", null)

        pending.pruneEchoed(listOf(theirs("привет", now)))

        assertEquals(1, pending.all.size)
    }

    @Test
    fun twoOfTheSameAreRetiredOneEchoAtATime() {
        val pending = PendingSends()
        pending.add("ок", null)
        pending.add("ок", null)

        pending.pruneEchoed(listOf(mine("ок", now)))
        assertEquals(1, pending.all.size)

        pending.pruneEchoed(listOf(mine("ок", now), mine("ок", now.plusMillis(1))))
        assertTrue(pending.isEmpty)
    }

    /** Уходит старший: оба говорят одно, на экране разницы нет, но ждёт — *новейшая* отправка. */
    @Test
    fun theOldestOfTwoIdenticalMessagesIsTheOneRetired() {
        val pending = PendingSends()
        val first = pending.add("ок", null, at = now.minusSeconds(10))
        val second = pending.add("ок", null, at = now)

        pending.pruneEchoed(listOf(mine("ок", now)))

        assertEquals(listOf(second), pending.all.map { it.id })
        assertFalse(first in pending.all.map { it.id })
    }

    /** Сообщение часовой давности — не эхо отправленного секунду назад: снять пузырь значило бы оставить пустоту. */
    @Test
    fun anOldMessageOfOursIsNotMistakenForTheEcho() {
        val pending = PendingSends()
        pending.add("привет", null)

        pending.pruneEchoed(listOf(mine("привет", now.minusSeconds(3600))))

        assertEquals(1, pending.all.size)
    }

    /** Перекос, записанный тестом: эхо со временем чуть *до* нажатия — часы, пауза очереди — считается. */
    @Test
    fun anEchoTimestampedJustBeforeThePressStillCounts() {
        val pending = PendingSends()
        pending.add("привет", null)

        pending.pruneEchoed(listOf(mine("привет", now.minusSeconds(5))))

        assertTrue(pending.isEmpty)
    }

    @Test
    fun aMessageTheSdkRefusedIsTakenBackByHand() {
        val pending = PendingSends()
        val id = pending.add("привет", null)
        pending.add("пока", null)

        pending.remove(id)

        assertEquals(listOf("пока"), pending.all.map { it.text })
    }

    @Test
    fun puttingTheRoomDownForgetsEverything() {
        val pending = PendingSends()
        pending.add("привет", null)
        pending.removeAll()

        assertTrue(pending.isEmpty)
    }

    /** Фото, голосовые и прочее SDK возвращает само; подпись с теми же словами — не наше эхо. */
    @Test
    fun onlyOurOwnTextMessagesCanBeAnEcho() {
        val pending = PendingSends()
        pending.add("привет", null)

        val notice = TimelineRow.MessageRow(
            Message("\$notice", MessageKind.Notice("привет"), me, "Я", null, true, now, DeliveryStatus.Sent),
        )
        pending.pruneEchoed(listOf(notice))

        assertEquals(1, pending.all.size)
    }

    @Test
    fun theEchoIsToldWhichBubbleItIsReplacing() {
        val pending = PendingSends()
        val id = pending.add("привет", null)

        val echo = mine("привет", now)
        val taken = pending.pruneEchoed(listOf(echo))

        assertEquals(id, taken[echo.id])
        assertTrue(pending.isEmpty)
    }

    @Test
    fun twoOfTheSameWordArePairedInTheOrderTheyWereSent() {
        val pending = PendingSends()
        val first = pending.add("ок", null)
        val second = pending.add("ок", null)

        val early = mine("ок", now)
        val late = mine("ок", now.plusSeconds(1))
        val taken = pending.pruneEchoed(listOf(early, late))

        assertEquals(first, taken[early.id])
        assertEquals(second, taken[late.id])
    }

    @Test
    fun nothingToPairWhenNothingWasEchoed() {
        val pending = PendingSends()
        pending.add("привет", null)

        assertTrue(pending.pruneEchoed(listOf(mine("другое", now))).isEmpty())
    }

    /** Своё, не из iOS: с упоминанием тело события — Markdown со ссылкой, и эхо сверяется с ним, а не с набранным. */
    @Test
    fun aMentionIsMatchedOnWhatWasSentNotWhatWasTyped() {
        val pending = PendingSends()
        val sent = "[@Аня](https://matrix.to/#/@anya:iskra.chat) привет"
        pending.add("@Аня привет", null, body = sent)

        pending.pruneEchoed(listOf(mine("@Аня привет", now)))
        assertEquals(1, pending.all.size)

        pending.pruneEchoed(listOf(mine(sent, now)))
        assertTrue(pending.isEmpty)
    }
}
