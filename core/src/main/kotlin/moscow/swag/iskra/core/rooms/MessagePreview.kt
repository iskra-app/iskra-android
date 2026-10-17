package moscow.swag.iskra.core.rooms

import moscow.swag.iskra.core.R
import moscow.swag.iskra.core.support.Strings
import moscow.swag.iskra.core.timeline.HtmlWords
import org.matrix.rustcomponents.sdk.FormattedBody
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.VideoMessageContent
import uniffi.matrix_sdk_ui.LatestEventValueLocalState
import java.time.Instant

/**
 * Содержимое события → одна строка под именем чата. Порт `MessagePreview.swift`.
 *
 * Намеренно с потерями. Вложения становятся «Фото», «Голосовое» и т.д., а не утекают именем файла;
 * то, что показать нельзя, — null, а не заглушка со словарём протокола.
 */
internal object MessagePreview {
    fun kind(content: TimelineItemContent): PreviewKind? {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content ?: return null
        return when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> when (val type = kind.content.msgType) {
                is MessageType.Image ->
                    if (isSticker(type.content.info?.mimetype, type.content.filename)) PreviewKind.STICKER else PreviewKind.PHOTO
                is MessageType.Video -> if (isVideoNote(type.content)) PreviewKind.VIDEO_NOTE else PreviewKind.VIDEO
                is MessageType.Audio -> if (type.content.voice != null) PreviewKind.VOICE else PreviewKind.AUDIO
                is MessageType.File -> PreviewKind.FILE
                is MessageType.Location -> PreviewKind.LOCATION
                else -> null
            }
            is MsgLikeKind.Sticker -> PreviewKind.STICKER
            is MsgLikeKind.Poll -> PreviewKind.POLL
            is MsgLikeKind.LiveLocation -> PreviewKind.LOCATION
            else -> null
        }
    }

    fun text(content: TimelineItemContent, strings: Strings): String? {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content ?: return null
        return when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> text(kind.content.msgType, strings)
            is MsgLikeKind.Sticker -> strings[R.string.preview_sticker]
            is MsgLikeKind.Poll -> strings[R.string.preview_poll]
            // Не `Message`: SDK сворачивает живую геопозицию в свой вид, и без этого случая строка
            // под именем была пустой, когда последним был чей-то live location.
            is MsgLikeKind.LiveLocation -> strings[R.string.preview_live_location]
            is MsgLikeKind.Redacted -> strings[R.string.preview_deleted]
            // Ожидаемо, не ошибка: устройство вошло после сообщения и ключа не получило. Сказать без тревоги.
            is MsgLikeKind.UnableToDecrypt -> strings[R.string.preview_unreadable]
            else -> null
        }
    }

    /**
     * Слова одного сообщения, какой бы дверью оно ни пришло: список читает `TimelineItemContent`,
     * пуш — `MessageLikeEventContent`, и у обоих внутри `MessageType`. Фото — «Фото» и в списке, и
     * на экране блокировки, потому что это одно предложение, написанное один раз.
     */
    fun text(type: MessageType, strings: Strings): String? = when (type) {
        // Слова самого человека — не строка для перевода; но читаются из форматирования, не из разметки.
        is MessageType.Text -> words(type.content.body, type.content.formatted)
        is MessageType.Notice -> words(type.content.body, type.content.formatted)
        is MessageType.Emote -> words(type.content.body, type.content.formatted)
        is MessageType.Image -> strings[
            if (isSticker(type.content.info?.mimetype, type.content.filename)) R.string.preview_sticker else R.string.preview_photo,
        ]
        is MessageType.Video -> strings[if (isVideoNote(type.content)) R.string.preview_video_note else R.string.preview_video]
        // `voice` отличает сказанное от музыкального файла: его ставят другие клиенты Matrix, поэтому
        // читается он, а не догадка по MIME.
        is MessageType.Audio -> strings[if (type.content.voice != null) R.string.preview_voice else R.string.preview_audio]
        is MessageType.File -> strings[R.string.preview_file]
        is MessageType.Location -> strings[R.string.preview_location]
        else -> null
    }

    fun words(body: String, formatted: FormattedBody?): String {
        if (formatted == null || formatted.format !is MessageFormat.Html) return body
        return HtmlWords.words(body, formatted.body)
    }

    /** Время Matrix — миллисекунды от эпохи. */
    fun instant(timestamp: ULong): Instant = Instant.ofEpochMilli(timestamp.toLong())

    /** Стикер, пришедший картинкой: WebP или файл `sticker.png|webp|gif` (`StickerPicture.isSticker`). */
    fun isSticker(mimeType: String?, filename: String?): Boolean {
        if (mimeType?.lowercase() == WEBP) return true
        val name = filename?.lowercase() ?: return false
        if (!name.startsWith(STICKER_PREFIX)) return false
        return name.removePrefix(STICKER_PREFIX) in STICKER_EXTENSIONS
    }

    /** Кружочек: квадратное видео не длиннее минуты (`VideoNote.isVideoNote`). */
    fun isVideoNote(content: VideoMessageContent): Boolean {
        val info = content.info ?: return false
        val width = info.width ?: return false
        val height = info.height ?: return false
        val duration = info.duration ?: return false
        return width == height && width > 0uL && duration <= VIDEO_NOTE_MAX
    }

    /**
     * Последнее событие чата по `latestEvent()` SDK — что положить в строку.
     *
     * [readUpTo] — докуда твои сообщения известны прочитанными: квитанций в `latestEvent` нет, они
     * только в ленте, и открытый чат сообщает их списку (`noteRead`).
     */
    fun lastMessage(latest: LatestEventValue, strings: Strings, readUpTo: Instant?): LastMessage? = when (latest) {
        is LatestEventValue.Remote -> {
            val date = instant(latest.timestamp)
            val sender = if (latest.isOwn) {
                null
            } else {
                (latest.profile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotEmpty() } ?: latest.sender
            }
            LastMessage(
                text = text(latest.content, strings),
                activity = date,
                kind = kind(latest.content),
                mark = if (!latest.isOwn) null else if (readUpTo != null && date <= readUpTo) LastMessageMark.READ else LastMessageMark.SENT,
                sender = sender,
            )
        }
        is LatestEventValue.Local -> LastMessage(
            text = text(latest.content, strings),
            activity = instant(latest.timestamp),
            kind = kind(latest.content),
            mark = when (latest.state) {
                LatestEventValueLocalState.IS_SENDING -> LastMessageMark.SENDING
                LatestEventValueLocalState.HAS_BEEN_SENT -> LastMessageMark.SENT
                LatestEventValueLocalState.CANNOT_BE_SENT -> LastMessageMark.FAILED
            },
            sender = null,
        )
        else -> null
    }

    private const val WEBP = "image/webp"
    private const val STICKER_PREFIX = "sticker."
    private val STICKER_EXTENSIONS = setOf("png", "webp", "gif")
    private val VIDEO_NOTE_MAX: java.time.Duration = java.time.Duration.ofSeconds(60)
}
