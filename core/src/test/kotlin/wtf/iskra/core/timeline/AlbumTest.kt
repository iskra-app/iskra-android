package wtf.iskra.core.timeline

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.NoHandle
import java.time.Instant

/** Порт `AlbumTests.swift`: какие фото становятся одним альбомом. */
class AlbumTest {
    private fun photo(id: String, second: Long, sender: String = "@a:example.org", caption: String? = null, replying: Boolean = false): TimelineRow {
        // Источник без нативной половины: группировке нужен только тип вложения и подпись.
        val attachment = Attachment(MediaSource(NoHandle), null, "$id.jpg", caption, 400, 300, null, "image/jpeg", null)
        return TimelineRow.MessageRow(
            Message(
                id = id, kind = MessageKind.Image(attachment), senderId = sender, senderName = "А", senderAvatarUrl = null,
                isOwn = false, timestamp = Instant.ofEpochSecond(second), delivery = null,
                replyTo = if (replying) ReplyPreview("\$q", "Б", "@b:example.org", "?") else null,
            ),
        )
    }

    private fun text(id: String, second: Long) = TimelineRow.MessageRow(
        Message(id, MessageKind.Text("…"), "@a:example.org", "А", null, false, Instant.ofEpochSecond(second), null),
    )

    /** Как выглядят строки после группировки: id сообщений каждой, через «+». */
    private fun shape(rows: List<TimelineRow>) = Album.grouping(rows).map { row ->
        (row as? TimelineRow.MessageRow)?.message?.members?.joinToString("+") { it.id } ?: row.id
    }

    @Test
    fun photosSentTogetherAreOneRowNamedAfterTheFirst() {
        val rows = Album.grouping(listOf(photo("a", 0), photo("b", 2), photo("c", 5)))
        assertEquals(1, rows.size)
        assertEquals("a", rows[0].id)
        assertEquals(listOf("a", "b", "c"), (rows[0] as TimelineRow.MessageRow).message.album?.map { it.id })
    }

    @Test
    fun aLongPauseKeepsThemApart() {
        assertEquals(listOf("a", "b"), shape(listOf(photo("a", 0), photo("b", 60))))
    }

    @Test
    fun theGapIsMeasuredBetweenNeighbours() {
        assertEquals(listOf("p0+p1+p2+p3+p4"), shape((0 until 5).map { photo("p$it", it * 12L) }))
    }

    @Test
    fun twoPeoplesPhotosAreNeverOneAlbum() {
        assertEquals(listOf("a", "b"), shape(listOf(photo("a", 0), photo("b", 1, sender = "@b:example.org"))))
    }

    @Test
    fun anythingSaidInBetweenBreaksIt() {
        assertEquals(listOf("a", "t", "b"), shape(listOf(photo("a", 0), text("t", 1), photo("b", 2))))
    }

    @Test
    fun aCaptionMayOnlyOpenAnAlbum() {
        val rows = listOf(photo("a", 0, caption = "море"), photo("b", 1), photo("c", 2))
        assertEquals(listOf("a+b+c"), shape(rows))
        assertEquals("море", (Album.grouping(rows).first() as TimelineRow.MessageRow).message.albumCaption)
        assertEquals(listOf("a", "b+c"), shape(listOf(photo("a", 0), photo("b", 1, caption = "пляж"), photo("c", 2))))
    }

    @Test
    fun aReplyMayOnlyStartAnAlbum() {
        assertEquals(listOf("a+b"), shape(listOf(photo("a", 0, replying = true), photo("b", 1))))
        assertEquals(listOf("a", "b"), shape(listOf(photo("a", 0), photo("b", 1, replying = true))))
    }

    @Test
    fun tenToAnAlbum() {
        val rows = (0 until 12).map { photo("p$it", it.toLong()) }
        assertEquals(listOf(10, 2), Album.grouping(rows).map { (it as TimelineRow.MessageRow).message.members.size })
    }

    @Test
    fun aPictureInsideAnAlbumIsFoundByItsOwnId() {
        assertEquals("b", Album.grouping(listOf(photo("a", 0), photo("b", 1))).message(withId = "b")?.id)
    }
}
