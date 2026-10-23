package wtf.iskra.core.rooms

import wtf.iskra.core.R
import wtf.iskra.core.support.Strings
import wtf.iskra.core.timeline.DeliveryStatus
import wtf.iskra.core.timeline.HtmlWords
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.TimelineRow
import java.time.Instant

/**
 * Что говорит строка чата, прочитанное с *самого чата*, а не с краткого пересказа SDK. Порт `PeekLine`.
 *
 * `latestEvent()` SDK — *отфильтрованный* ответ, и два отфильтрованных им — то, что списку нужно показать. Удалённое
 * сообщение пропускается: SDK идёт мимо вычеркнутого события к предыдущему, и строка продолжала цитировать слова, которые
 * человек взял назад. И пропускаются все серые строки — «Аня вошла», «чат переименован»: строка показывала то, что
 * говорили до них, будто их не было. Telegram кладёт и то, и другое в список, и открытый чат согласен со строкой.
 *
 * Третье — отметка «прочитано». У последнего события квитанций нет, их держит только лента, поэтому своё сообщение,
 * прочитанное на той стороне, оставалось в списке «отправленным», пока не откроешь чат.
 */
internal data class PeekLine(
    val text: String,
    val kind: PreviewKind?,
    /** Ход твоего последнего сообщения; null у чужого и у серой строки. */
    val mark: LastMessageMark?,
    val date: Instant,
    /** Кто сказал, если это не ты — см. `RoomSummary.lastSender`. */
    val sender: String? = null,
) {
    companion object {
        /**
         * Последнее, что строка может сказать. Null — что-то, для чего у отображения нет слов (событие, которое оно не
         * рисует), и тогда спрашивается пересказ SDK.
         */
        fun last(rows: List<TimelineRow>, strings: Strings): PeekLine? {
            for (row in rows.asReversed()) {
                when (row) {
                    is TimelineRow.MessageRow -> {
                        val message = row.message
                        if (message.kind == MessageKind.Unsupported) return null
                        val deleted = message.kind == MessageKind.Deleted
                        return PeekLine(
                            text = text(message, strings),
                            kind = previewKind(message.kind),
                            // Удалённое никто не ждёт прочитанным.
                            mark = if (message.isOwn && !deleted) message.delivery?.let(::mark) else null,
                            date = message.timestamp,
                            sender = if (message.isOwn) null else message.senderName,
                        )
                    }
                    is TimelineRow.EventRow -> return PeekLine(row.event.text(strings), null, null, row.event.timestamp)
                    is TimelineRow.DayDividerRow, TimelineRow.UnreadDivider -> continue
                }
            }
            return null
        }

        /** Одно сообщение в одну строку; у текста — слова из HTML, если он есть. */
        fun text(message: Message, strings: Strings): String = when (val kind = message.kind) {
            is MessageKind.Text -> HtmlWords.words(kind.body, message.formattedBody)
            is MessageKind.Notice -> HtmlWords.words(kind.body, message.formattedBody)
            is MessageKind.Emote -> HtmlWords.words(kind.body, message.formattedBody)
            is MessageKind.Image -> kind.attachment.caption ?: strings[R.string.preview_photo]
            is MessageKind.Sticker -> strings[R.string.preview_sticker]
            is MessageKind.Video -> strings[R.string.preview_video]
            is MessageKind.VideoNote -> strings[R.string.preview_video_note]
            is MessageKind.Voice -> strings[R.string.preview_voice]
            is MessageKind.Audio -> strings[R.string.preview_audio]
            is MessageKind.File -> strings[R.string.preview_file]
            is MessageKind.Location -> strings[if (kind.live != null) R.string.preview_live_location else R.string.preview_location]
            is MessageKind.Poll -> kind.poll.question
            MessageKind.Deleted -> strings[R.string.preview_deleted]
            MessageKind.Unreadable -> strings[R.string.preview_unreadable]
            MessageKind.Unsupported -> ""
        }

        /** Значок перед словом вложения в списке — у уже разобранного сообщения. */
        fun previewKind(kind: MessageKind): PreviewKind? = when (kind) {
            is MessageKind.Image -> PreviewKind.PHOTO
            is MessageKind.Sticker -> PreviewKind.STICKER
            is MessageKind.Video -> PreviewKind.VIDEO
            is MessageKind.VideoNote -> PreviewKind.VIDEO_NOTE
            is MessageKind.Voice -> PreviewKind.VOICE
            is MessageKind.Audio -> PreviewKind.AUDIO
            is MessageKind.File -> PreviewKind.FILE
            is MessageKind.Location -> PreviewKind.LOCATION
            is MessageKind.Poll -> PreviewKind.POLL
            is MessageKind.Text, is MessageKind.Emote, is MessageKind.Notice, MessageKind.Unreadable, MessageKind.Deleted, MessageKind.Unsupported -> null
        }

        fun mark(delivery: DeliveryStatus): LastMessageMark = when (delivery) {
            DeliveryStatus.Sending -> LastMessageMark.SENDING
            DeliveryStatus.Sent -> LastMessageMark.SENT
            DeliveryStatus.Read -> LastMessageMark.READ
            is DeliveryStatus.Failed -> LastMessageMark.FAILED
        }
    }
}
