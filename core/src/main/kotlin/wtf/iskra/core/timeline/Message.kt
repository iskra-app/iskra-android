package wtf.iskra.core.timeline

import wtf.iskra.core.R
import wtf.iskra.core.support.Strings
import org.matrix.rustcomponents.sdk.MediaSource
import java.time.Instant

/** Как далеко ушло наше сообщение. Только у своих. Порт `Message.swift`. */
sealed interface DeliveryStatus {
    data object Sending : DeliveryStatus
    data object Sent : DeliveryStatus

    /** Кто-то другой в комнате прочёл. */
    data object Read : DeliveryStatus

    /** Не ушло, и SDK сказал почему: причина и есть то, что делать. */
    data class Failed(val reason: SendFailure) : DeliveryStatus

    val hasFailed: Boolean get() = this is Failed
}

/**
 * Почему очередь отправки стоит на сообщении — по одному на `QueueWedgeError`, сведённые к тому,
 * что человек может сделать. Текст сервера сюда не попадает: он по-английски и для администратора.
 */
enum class SendFailure(val explanation: Int) {
    INSECURE_DEVICES(R.string.send_failure_insecure_devices),
    IDENTITY_CHANGED(R.string.send_failure_identity_changed),
    VERIFICATION_REQUIRED(R.string.send_failure_verification_required),
    MISSING_MEDIA(R.string.send_failure_missing_media),
    UNSUPPORTED_FILE_TYPE(R.string.send_failure_unsupported_file_type),
    SERVER(R.string.send_failure_server),
    ;

    /** Честно ли предлагать «Отправить ещё раз»: может ли препятствие уйти само. */
    val canRetry: Boolean get() = this == VERIFICATION_REQUIRED || this == SERVER

    /** Может ли человек решить отправить всё равно — решение о доверии, всегда его. */
    val canSendAnyway: Boolean get() = this == INSECURE_DEVICES || this == IDENTITY_CHANGED
}

/**
 * Скачиваемое медиа. Байты берутся лениво через `MediaLoader`: лента из сотни фото не тянет сотню фото.
 * Источник — тип SDK — internal: из `:core` он не выходит.
 */
class Attachment internal constructor(
    internal val source: MediaSource,
    /** Кадр, загруженный *отправителем*, — в зашифрованной комнате единственный путь к картинке видео. */
    internal val thumbnailSource: MediaSource?,
    val filename: String,
    val caption: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val mimeType: String?,
    val blurhash: String?,
    /** Сколько байт, как сказал отправитель. Только чтобы показать: качать решает человек. */
    val size: Long? = null,
) {
    /** Что опознаёт байты: `mxc://`, на который указывает событие. Имя файла — нет: у всех стикеров «sticker». */
    val mediaId: String = runCatching { source.url() }.getOrDefault("")

    val aspectRatio: Double? get() = if (width != null && height != null && height > 0) width.toDouble() / height else null

    override fun equals(other: Any?) = other is Attachment && other.filename == filename && other.caption == caption &&
        other.width == width && other.height == height && other.durationMs == durationMs &&
        other.blurhash == blurhash && other.mediaId == mediaId && other.size == size

    override fun hashCode() = mediaId.hashCode() * 31 + filename.hashCode()
}

/** Подпись под картинкой или видео; у остального — null. */
val MessageKind.caption: String?
    get() = when (this) {
        is MessageKind.Image -> attachment.caption
        is MessageKind.Video -> attachment.caption
        else -> null
    }

/** Что сообщение есть на самом деле. Экран выбирает по этому пузырь. */
sealed interface MessageKind {
    data class Text(val body: String) : MessageKind
    data class Emote(val body: String) : MessageKind
    data class Notice(val body: String) : MessageKind
    data class Image(val attachment: Attachment) : MessageKind

    /** Стикер — сказанное, а не вложение: без пузыря, без подписи, нечего открывать. */
    data class Sticker(val attachment: Attachment) : MessageKind
    data class Video(val attachment: Attachment) : MessageKind

    /** Кружочек. */
    data class VideoNote(val attachment: Attachment) : MessageKind
    data class Voice(val attachment: Attachment, val waveform: List<Float>) : MessageKind
    data class Audio(val attachment: Attachment) : MessageKind
    data class File(val attachment: Attachment) : MessageKind
    data class Location(val latitude: Double, val longitude: Double, val name: String?, val live: LiveSpan? = null) : MessageKind {
        /**
         * Чья-то геопозиция *на ходу* (MSC3489): последняя точка трансляции. Порт `Location.LiveSpan`.
         */
        data class LiveSpan(
            /** Говорит ли ещё сам делящийся, что трансляция идёт: остановить её можно раньше срока. */
            val isLive: Boolean,
            /** Когда она кончится сама. */
            val until: Instant,
            /** Когда пришла последняя точка; null, пока ни одной. */
            val updatedAt: Instant?,
        ) {
            /** Всё ещё движется по карте: включена и не вышла за срок. */
            fun isActive(now: Instant): Boolean = isLive && until.isAfter(now)

            /** Есть ли что показать на карте: только что начатая трансляция точки не имеет. */
            val hasPosition: Boolean get() = updatedAt != null
        }
    }
    data class Poll(val poll: wtf.iskra.core.timeline.Poll) : MessageKind

    /** Сообщение есть, но этому устройству не дали ключ. */
    data object Unreadable : MessageKind
    data object Deleted : MessageKind

    /** То, что ещё не рисуем. Честная пустота лучше неверной догадки. */
    data object Unsupported : MessageKind
}

/** Один эмодзи под сообщением и сколько людей его нажали. `isMine` — потому что повторное нажатие убирает. */
data class MessageReaction(
    val key: String,
    val count: Int,
    val isMine: Boolean,
    /** Кто оставил, по Matrix ID, ранние первыми. */
    val senders: List<String> = emptyList(),
)

/**
 * Сообщение, на которое отвечают, сжатое до полоски. *Копия*, не указатель: оригинал может быть за
 * тысячу сообщений и не загружен. `eventId` живёт, даже когда остальное нет: нажатие ведёт к нему.
 */
data class ReplyPreview(
    val eventId: String,
    /** Null, пока SDK ещё ищет оригинал или не нашёл. */
    val senderName: String?,
    val senderId: String? = null,
    /** Одна строка, уже сведённая: «Фото», «Голосовое» или сами слова. */
    val text: String,
)

/** Строка «3 ответа» под сообщением, на которое ответили в треде. */
data class ThreadSummaryPreview(val replies: Int, val latestSender: String?, val latestText: String?)

/** Одно сообщение, сплющенное во что-то, что Compose может сравнить. */
data class Message(
    val id: String,
    val kind: MessageKind,
    val senderId: String,
    val senderName: String,
    val senderAvatarUrl: String?,
    val isOwn: Boolean,
    val timestamp: Instant,
    /** Не `val` по сути: квитанция указывает на одно событие, и «прочитано» разносится вниз. */
    val delivery: DeliveryStatus?,
    /** Когда кто-то другой впервые прочёл — самая ранняя квитанция. */
    val readAt: Instant? = null,
    val isEdited: Boolean = false,
    val replyTo: ReplyPreview? = null,
    /** В порядке первого появления, чтобы пилюли не прыгали под пальцем. */
    val reactions: List<MessageReaction> = emptyList(),
    val mentionedUserIds: List<String> = emptyList(),
    /** HTML сообщения, как написал отправитель, — рисует пузырь. Null у простого. */
    val formattedBody: String? = null,
    /** В одном заходе с сообщением выше — имя и лицо только у первого. */
    val isContinuation: Boolean = false,
    /** И другой конец того же: под этим — продолжение. Лицо — у последнего в заходе. */
    val isContinued: Boolean = false,
    /** Одно сообщение несёт отметку «прочитано» за все — новейшее прочитанное своё. */
    val carriesReadMark: Boolean = false,
    val thread: ThreadSummaryPreview? = null,
    val threadRootId: String? = null,
    /**
     * Фото и видео, отправленные вместе, — у первого сообщения альбома (`Album`); у остальных и у
     * одиночных — null. Строка альбома — его первое сообщение.
     */
    val album: List<Message>? = null,
) {
    /** Все сообщения, за которые стоит эта строка: альбома или только оно само. */
    val members: List<Message> get() = album ?: listOf(this)

    /** Подпись альбома — первой картинки, единственной, у которой она может быть. */
    val albumCaption: String? get() = album?.firstOrNull()?.kind?.caption

    val isDeleted: Boolean get() = kind == MessageKind.Deleted

    private val isOnServer: Boolean get() = delivery == null || delivery == DeliveryStatus.Sent || delivery == DeliveryStatus.Read

    /** Не на удалённое (👍 под «сообщение удалено») и не на неотправленное — у него нет ID события. */
    val canReact: Boolean get() = !isDeleted && isOnServer

    /** Свои, дошедшие до сервера: у сообщения в очереди нечего удалять. */
    val canDelete: Boolean get() = isOwn && !isDeleted && delivery != null && isOnServer

    /** Ответить можно и на удалённое — цитата скажет правду; но не на неотправленное. */
    val canReply: Boolean get() = !isDeleted && isOnServer

    /** Что положить в композер при правке: слова сообщения. */
    val editableText: String?
        get() = when (kind) {
            is MessageKind.Text -> kind.body
            is MessageKind.Notice -> kind.body
            is MessageKind.Emote -> kind.body
            else -> null
        }

    /** Свой опрос, ещё открытый и дошедший до сервера. */
    val canEndPoll: Boolean get() = isOwn && isOnServer && (kind as? MessageKind.Poll)?.poll?.isEnded == false

    val canEdit: Boolean get() = isOwn && !isDeleted && editableText != null && delivery != null && isOnServer
}

/** Человек, названный в служебной строке, — имя и стоящий за ним Matrix ID. */
data class RoomEventPerson(val userId: String, val name: String)

/**
 * Что случилось в комнате, без слов, — *структура*, а не готовая строка: два входа подряд схлопываются
 * в одну строку, а схлопывать предложения нельзя.
 */
sealed interface RoomEventKind {
    data class Joined(override val people: List<RoomEventPerson>) : RoomEventKind
    data class Left(override val people: List<RoomEventPerson>) : RoomEventKind
    data class Invited(override val people: List<RoomEventPerson>) : RoomEventKind
    data class Removed(override val people: List<RoomEventPerson>) : RoomEventKind
    data class Banned(override val people: List<RoomEventPerson>) : RoomEventKind
    data class RenamedThemselves(val from: String, val to: String) : RoomEventKind
    data class RoomRenamed(val name: String) : RoomEventKind
    data object RoomTopicChanged : RoomEventKind
    data object RoomPictureChanged : RoomEventKind
    data object RoomCreated : RoomEventKind
    data object EncryptionEnabled : RoomEventKind

    /** То, что приложение обычно не показывает (права, правила входа, звонки, не разобранное): серая строка с типом и отправителем — только при «Бета → все события». */
    data class Hidden(val description: String) : RoomEventKind

    /** Названные люди — у видов, которые называют людей; null у остальных, и тогда сливать нечего. */
    val people: List<RoomEventPerson>? get() = null

    /**
     * То же событие с большим числом людей; null, где двум не место в одной строке. Только подобное с
     * подобным, и один человек (по ID) не попадает в строку дважды.
     */
    fun merged(other: RoomEventKind): RoomEventKind? {
        val mine = people ?: return null
        val theirs = other.people ?: return null
        val combined = mine + theirs.filter { person -> mine.none { it.userId == person.userId } }
        return when {
            this is Joined && other is Joined -> Joined(combined)
            this is Left && other is Left -> Left(combined)
            this is Invited && other is Invited -> Invited(combined)
            this is Removed && other is Removed -> Removed(combined)
            this is Banned && other is Banned -> Banned(combined)
            else -> null
        }
    }
}

/** Что-то, что случилось в комнате и что никто не говорил: одна серая строка посередине. */
data class RoomEvent(val id: String, val kind: RoomEventKind, val timestamp: Instant, val actorId: String? = null) {
    /** Люди, которых называет строка, — чтобы имя в серой строке можно было нажать. */
    val people: List<RoomEventPerson>
        get() = kind.people ?: (kind as? RoomEventKind.RenamedThemselves)?.let { renamed ->
            actorId?.let { listOf(RoomEventPerson(it, renamed.to)) }
        }.orEmpty()

    /** Строка на языке читающего. Собирается здесь: экран получает предложение и красит его серым. */
    fun text(strings: Strings): String = when (val kind = kind) {
        is RoomEventKind.Joined -> strings.format(R.string.room_event_joined, list(kind.people, strings))
        is RoomEventKind.Left -> strings.format(R.string.room_event_left, list(kind.people, strings))
        is RoomEventKind.Invited -> strings.format(R.string.room_event_invited, list(kind.people, strings))
        is RoomEventKind.Removed -> strings.format(R.string.room_event_removed, list(kind.people, strings))
        is RoomEventKind.Banned -> strings.format(R.string.room_event_banned, list(kind.people, strings))
        is RoomEventKind.RenamedThemselves -> strings.format(R.string.room_event_renamed_themselves, kind.from, kind.to)
        is RoomEventKind.RoomRenamed -> strings.format(R.string.room_event_room_renamed, kind.name)
        RoomEventKind.RoomTopicChanged -> strings[R.string.room_event_room_topic_changed]
        RoomEventKind.RoomPictureChanged -> strings[R.string.room_event_room_picture_changed]
        RoomEventKind.RoomCreated -> strings[R.string.room_event_room_created]
        RoomEventKind.EncryptionEnabled -> strings[R.string.room_event_encryption_enabled]
        is RoomEventKind.Hidden -> kind.description
    }

    /** Один — имя; двое — оба; трое и больше — первое и счёт: строка из одиннадцати имён — абзац. */
    private fun list(people: List<RoomEventPerson>, strings: Strings): String = when (people.size) {
        0 -> ""
        1 -> people[0].name
        2 -> strings.format(R.string.room_event_pair, people[0].name, people[1].name)
        else -> strings.plural(R.plurals.room_event_others, people.size - 1, people[0].name, people.size - 1)
    }
}

data class DayDivider(val id: String, val date: Instant)

/** Сообщение, дата над ним или заметка о случившемся. */
sealed interface TimelineRow {
    val id: String

    data class MessageRow(override val message: Message) : TimelineRow {
        override val id: String get() = message.id
    }

    data class DayDividerRow(val divider: DayDivider) : TimelineRow {
        override val id: String get() = divider.id
    }

    data class EventRow(val event: RoomEvent) : TimelineRow {
        override val id: String get() = event.id
    }

    /** «Непрочитанные сообщения»: где читающий остановился в прошлый раз. Он один на ленту. */
    data object UnreadDivider : TimelineRow {
        override val id: String get() = UNREAD_DIVIDER_ID
    }

    val isDivider: Boolean get() = this is DayDividerRow || this == UnreadDivider
    val message: Message? get() = null

    companion object {
        const val UNREAD_DIVIDER_ID = "unread-divider"
    }
}
