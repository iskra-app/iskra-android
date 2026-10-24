package wtf.iskra.core.timeline

import wtf.iskra.core.R
import wtf.iskra.core.rooms.MessagePreview
import wtf.iskra.core.support.Strings
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.ThreadSummary
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.MessageContent
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PollKind
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.QueueWedgeError
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.VirtualTimelineItem
import java.time.Instant
import java.util.IdentityHashMap

/**
 * Элементы ленты SDK → value-типы для экрана. Единственное место, знающее `MsgLikeKind`,
 * `EventSendState` и прочий словарь SDK. Порт `TimelineMapper.swift` (половина, которой нужен SDK;
 * чистая половина — [TimelineRows]).
 */
internal object TimelineMapper {
    /** Волна голосового на проводе — `0…1024` на отсчёт; экрану нужно `0…1`. */
    const val WAVEFORM_SCALE = 1024f

    /**
     * Уже разобранные элементы — по *тому самому* объекту, что отдал SDK. Без этого каждый дифф
     * (квитанция, реакция) разбирал всю ленту заново, а `asEvent()` — это вся запись через FFI.
     * Элемент SDK — неизменяемый снимок: перемена приходит `set`ом с *новым* объектом.
     */
    class Memo {
        private var entries = IdentityHashMap<TimelineItem, Mapped>()

        /** Ответы из последнего разбора, оригиналов которых нет, — в порядке ленты. */
        var unresolvedReplies: List<String> = emptyList()
            private set

        fun mapped(items: List<TimelineItem>, ownUserId: String, strings: Strings): List<Mapped> {
            val next = IdentityHashMap<TimelineItem, Mapped>(items.size)
            val replies = mutableListOf<String>()
            val result = items.map { item ->
                val mapped = entries[item] ?: map(item, ownUserId, strings)
                next[item] = mapped
                mapped.unresolvedReply?.let(replies::add)
                mapped
            }
            entries = next
            unresolvedReplies = replies
            return result
        }

        fun clear() {
            entries = IdentityHashMap()
            unresolvedReplies = emptyList()
        }
    }

    fun map(item: TimelineItem, ownUserId: String, strings: Strings): Mapped {
        item.asVirtual()?.let { virtual ->
            val body = when (virtual) {
                is VirtualTimelineItem.DateDivider ->
                    Mapped.Body.DayDividerBody(DayDivider("divider-${virtual.ts}", MessagePreview.instant(virtual.ts)))
                is VirtualTimelineItem.ReadMarker -> Mapped.Body.ReadMarker
                else -> Mapped.Body.Nothing
            }
            return Mapped(body)
        }

        // Один раз: это дорогой вызов — вся запись, поднятая из Rust.
        val event = item.asEvent() ?: return Mapped(Mapped.Body.Nothing)
        val foreign = hasForeignReceipt(event, ownUserId)
        // Остальное не выбрасывается, а получает имя: показывать ли его, решает `ServiceEventSettings` («Бета → все события»).
        val body = roomEvent(event)?.let { Mapped.Body.EventBody(it) }
            ?: message(event, ownUserId, strings)?.let { Mapped.Body.MessageBody(it) }
            ?: Mapped.Body.EventBody(
                RoomEvent(identifier(event.eventOrTransactionId), RoomEventKind.Hidden(hiddenDescription(event.content, event.sender)), MessagePreview.instant(event.timestamp), event.sender),
            )
        return Mapped(
            body = body,
            hasForeignReceipt = foreign,
            receiptReadAt = if (foreign) readAt(event, ownUserId) else null,
            unresolvedReply = unresolvedReply(event),
        )
    }

    fun identifier(id: EventOrTransactionId): String = when (id) {
        is EventOrTransactionId.EventId -> id.eventId
        is EventOrTransactionId.TransactionId -> id.transactionId
    }

    private fun message(event: EventTimelineItem, ownUserId: String, strings: Strings): Message? {
        val kind = kind(event.content, ownUserId) ?: return null
        val (name, avatar) = profile(event.senderProfile, event.sender)
        val msgLike = (event.content as? TimelineItemContent.MsgLike)?.content
        return Message(
            id = identifier(event.eventOrTransactionId),
            kind = kind,
            senderId = event.sender,
            senderName = name,
            senderAvatarUrl = avatar,
            isOwn = event.isOwn,
            timestamp = MessagePreview.instant(event.timestamp),
            delivery = if (event.isOwn) delivery(event, ownUserId) else null,
            readAt = if (event.isOwn) readAt(event, ownUserId) else null,
            uploadProgress = if (event.isOwn) uploadProgress(event.localSendState) else null,
            isEdited = ((msgLike?.kind as? MsgLikeKind.Message)?.content?.isEdited) == true,
            replyTo = replyPreview(event.content, strings),
            reactions = reactions(event.content, ownUserId),
            mentionedUserIds = (msgLike?.kind as? MsgLikeKind.Message)?.content?.mentions?.userIds.orEmpty(),
            formattedBody = formattedBody(event.content),
            threadRootId = msgLike?.threadRoot,
            thread = msgLike?.threadSummary?.let { threadPreview(it, strings) },
        )
    }

    /**
     * То, что происходит с комнатой, а не в ней. Всё, о чём приложению нечего сказать (права,
     * правила входа, ACL), — null и выбрасывается: переписка — не журнал аудита. Смена одной лишь
     * аватарки — тоже: новое лицо видно на следующем сообщении.
     */
    /** Событие словами протокола: тип, подробность, если она говорит больше типа, и кто отправил. */
    private fun hiddenDescription(content: TimelineItemContent, sender: String): String {
        val what = when (content) {
            is TimelineItemContent.RoomMembership -> "m.room.member · ${content.userId} · ${content.change}"
            is TimelineItemContent.ProfileChange -> "m.room.member · profile"
            is TimelineItemContent.State -> content.content::class.simpleName.orEmpty().let { name ->
                if (content.stateKey.isEmpty()) name else "$name · ${content.stateKey}"
            }
            is TimelineItemContent.CallInvite -> "m.call.invite"
            is TimelineItemContent.RtcNotification -> "org.matrix.msc4075.rtc.notification"
            is TimelineItemContent.FailedToParseMessageLike -> "${content.eventType} · ${content.error}"
            is TimelineItemContent.FailedToParseState -> "${content.eventType} · ${content.stateKey} · ${content.error}"
            is TimelineItemContent.MsgLike -> content.content.kind::class.simpleName.orEmpty()
        }
        return "$what — $sender"
    }

    private fun roomEvent(event: EventTimelineItem): RoomEvent? {
        val kind = roomEventKind(event.content) ?: return null
        return RoomEvent(identifier(event.eventOrTransactionId), kind, MessagePreview.instant(event.timestamp), event.sender)
    }

    private fun roomEventKind(content: TimelineItemContent): RoomEventKind? = when (content) {
        is TimelineItemContent.RoomMembership -> {
            // Имя, которым его знает комната, и Matrix ID, если имени нет.
            val who = listOf(RoomEventPerson(content.userId, content.userDisplayName ?: content.userId))
            when (content.change) {
                MembershipChange.JOINED, MembershipChange.INVITATION_ACCEPTED, MembershipChange.KNOCK_ACCEPTED -> RoomEventKind.Joined(who)
                MembershipChange.LEFT, MembershipChange.INVITATION_REJECTED, MembershipChange.KNOCK_RETRACTED -> RoomEventKind.Left(who)
                MembershipChange.INVITED -> RoomEventKind.Invited(who)
                MembershipChange.KICKED, MembershipChange.INVITATION_REVOKED, MembershipChange.KNOCK_DENIED -> RoomEventKind.Removed(who)
                MembershipChange.BANNED, MembershipChange.KICKED_AND_BANNED -> RoomEventKind.Banned(who)
                else -> null
            }
        }
        is TimelineItemContent.ProfileChange -> {
            // Только смена *имени*, и только когда известны обе половины.
            val to = content.displayName
            val from = content.prevDisplayName
            if (to != null && from != null && to != from) RoomEventKind.RenamedThemselves(from, to) else null
        }
        is TimelineItemContent.State -> when (val state = content.content) {
            is OtherState.RoomName -> state.name?.let { RoomEventKind.RoomRenamed(it) }
            is OtherState.RoomTopic -> RoomEventKind.RoomTopicChanged
            is OtherState.RoomAvatar -> RoomEventKind.RoomPictureChanged
            is OtherState.RoomCreate -> RoomEventKind.RoomCreated
            is OtherState.RoomEncryption -> RoomEventKind.EncryptionEnabled
            else -> null
        }
        else -> null
    }

    /**
     * Ответ, оригинала которого SDK не держит. `Unavailable` — не «искал и не нашёл», а «никто не
     * просил искать»: детали подтягиваются по `fetchDetailsForEvent`, и стор просит. ID — самого ответа.
     */
    private fun unresolvedReply(event: EventTimelineItem): String? {
        val id = event.eventOrTransactionId as? EventOrTransactionId.EventId ?: return null
        val details = (event.content as? TimelineItemContent.MsgLike)?.content?.inReplyTo ?: return null
        return if (details.event() is EmbeddedEventDetails.Unavailable) id.eventId else null
    }

    /** Сообщение, на которое отвечают, как его разрешил SDK, — все три ответа сказаны вслух. */
    /** «3 ответа · Аня: последнее» — последний ответ, если SDK его уже знает. */
    private fun threadPreview(summary: ThreadSummary, strings: Strings): ThreadSummaryPreview {
        val latest = summary.latestEvent() as? EmbeddedEventDetails.Ready
        val sender = latest?.let { profile(it.senderProfile, it.sender).first }
        val text = latest?.let { MessagePreview.text(it.content, strings) }
        return ThreadSummaryPreview(summary.numReplies().toInt(), sender, text)
    }

    private fun replyPreview(content: TimelineItemContent, strings: Strings): ReplyPreview? {
        val details = (content as? TimelineItemContent.MsgLike)?.content?.inReplyTo ?: return null
        val eventId = details.eventId()
        return when (val original = details.event()) {
            is EmbeddedEventDetails.Ready -> {
                val (name, _) = profile(original.senderProfile, original.sender)
                ReplyPreview(eventId, name, original.sender, MessagePreview.text(original.content, strings) ?: strings[R.string.reply_unavailable])
            }
            is EmbeddedEventDetails.Pending -> ReplyPreview(eventId, null, text = strings[R.string.reply_loading])
            else -> ReplyPreview(eventId, null, text = strings[R.string.reply_unavailable])
        }
    }

    /**
     * Эмодзи под сообщением, по времени *первого* появления — чтобы пилюля не прыгала под пальцем.
     * Эмодзи без отправителей выбрасывается: «0» — ложь о том, чего уже нет.
     */
    private fun reactions(content: TimelineItemContent, ownUserId: String): List<MessageReaction> {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content ?: return emptyList()
        return msgLike.reactions
            .filter { it.senders.isNotEmpty() }
            .sortedBy { reaction -> reaction.senders.minOf { it.timestamp } }
            .map { reaction ->
                MessageReaction(
                    key = reaction.key,
                    count = reaction.senders.size,
                    isMine = reaction.senders.any { it.senderId == ownUserId },
                    senders = reaction.senders.sortedBy { it.timestamp }.map { it.senderId },
                )
            }
    }

    private fun kind(content: TimelineItemContent, ownUserId: String): MessageKind? {
        val msgLike = (content as? TimelineItemContent.MsgLike)?.content ?: return null
        return when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> kind(kind.content)
            is MsgLikeKind.Sticker -> MessageKind.Sticker(
                Attachment(
                    kind.source, kind.info.thumbnailSource, "sticker", kind.body.ifEmpty { null },
                    kind.info.width?.toInt(), kind.info.height?.toInt(), null, kind.info.mimetype, kind.info.blurhash,
                ),
            )
            is MsgLikeKind.Poll -> MessageKind.Poll(
                Poll.of(
                    question = kind.question,
                    isDisclosed = kind.kind == PollKind.DISCLOSED,
                    maxSelections = kind.maxSelections.toLong(),
                    answers = kind.answers.map { it.id to it.text },
                    votes = kind.votes,
                    isEnded = kind.endTime != null,
                    ownUserId = ownUserId,
                ),
            )
            is MsgLikeKind.LiveLocation -> {
                // SDK собирает точки трансляции под событием, с которого она началась; последняя — где
                // человек сейчас. До первой карте не на что встать, и она это говорит (`hasPosition`),
                // а не рисует пин у берегов Африки.
                val content = kind.content
                val last = content.locations.lastOrNull()
                val point = last?.let { share -> geo(share.geoUri)?.let { share to it } }
                val (lat, lon) = point?.second ?: (0.0 to 0.0)
                MessageKind.Location(
                    lat, lon, content.description,
                    MessageKind.Location.LiveSpan(
                        isLive = content.isLive,
                        until = MessagePreview.instant(content.ts).plusMillis(content.timeoutMs.toLong()),
                        updatedAt = point?.let { MessagePreview.instant(it.first.ts) },
                    ),
                )
            }
            is MsgLikeKind.Redacted -> MessageKind.Deleted
            is MsgLikeKind.UnableToDecrypt -> MessageKind.Unreadable
            else -> MessageKind.Unsupported
        }
    }

    private fun kind(message: MessageContent): MessageKind = when (val type = message.msgType) {
        is MessageType.Text -> MessageKind.Text(type.content.body)
        is MessageType.Emote -> MessageKind.Emote(type.content.body)
        is MessageType.Notice -> MessageKind.Notice(type.content.body)
        is MessageType.Image -> {
            val c = type.content
            val attachment = Attachment(
                c.source, c.info?.thumbnailSource, c.filename, c.caption,
                c.info?.width?.toInt(), c.info?.height?.toInt(), null, c.info?.mimetype, c.info?.blurhash,
            )
            // WebP — стикер, как бы он ни пришёл (в зашифрованную комнату приложение шлёт его картинкой).
            if (MessagePreview.isSticker(c.info?.mimetype, c.filename)) MessageKind.Sticker(attachment) else MessageKind.Image(attachment)
        }
        is MessageType.Video -> {
            val c = type.content
            val attachment = Attachment(
                c.source, c.info?.thumbnailSource, c.filename, c.caption,
                c.info?.width?.toInt(), c.info?.height?.toInt(), c.info?.duration?.toMillis(), c.info?.mimetype, c.info?.blurhash,
                c.info?.size?.toLong(),
            )
            if (MessagePreview.isVideoNote(c)) MessageKind.VideoNote(attachment) else MessageKind.Video(attachment)
        }
        is MessageType.Audio -> {
            val c = type.content
            val attachment = Attachment(
                c.source, null, c.filename, c.caption, null, null, c.info?.duration?.toMillis(), c.info?.mimetype, null, c.info?.size?.toLong(),
            )
            if (c.voice != null) {
                MessageKind.Voice(attachment, c.audio?.waveform.orEmpty().map { it.toFloat() / WAVEFORM_SCALE })
            } else {
                MessageKind.Audio(attachment)
            }
        }
        is MessageType.Location -> geo(type.content.geoUri)?.let { (lat, lon) -> MessageKind.Location(lat, lon, type.content.description) }
            ?: MessageKind.Unsupported
        is MessageType.File -> {
            val c = type.content
            MessageKind.File(
                Attachment(c.source, c.info?.thumbnailSource, c.filename, c.caption, null, null, null, c.info?.mimetype, null, c.info?.size?.toLong()),
            )
        }
        else -> MessageKind.Unsupported
    }

    /** `geo:55.75,37.59;u=10` → широта и долгота; непонятный URI — не место (не точка посреди Атлантики). */
    fun geo(uri: String): Pair<Double, Double>? {
        if (!uri.startsWith("geo:")) return null
        val parts = uri.removePrefix("geo:").substringBefore(';').split(',')
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun formattedBody(content: TimelineItemContent): String? {
        val message = ((content as? TimelineItemContent.MsgLike)?.content?.kind as? MsgLikeKind.Message)?.content ?: return null
        val formatted = when (val type = message.msgType) {
            is MessageType.Text -> type.content.formatted
            is MessageType.Emote -> type.content.formatted
            is MessageType.Notice -> type.content.formatted
            is MessageType.Image -> type.content.formattedCaption
            is MessageType.Video -> type.content.formattedCaption
            is MessageType.Audio -> type.content.formattedCaption
            is MessageType.File -> type.content.formattedCaption
            else -> null
        } ?: return null
        return if (formatted.format is MessageFormat.Html) formatted.body else null
    }

    private fun profile(details: ProfileDetails, fallback: String): Pair<String, String?> =
        if (details is ProfileDetails.Ready) (details.displayName ?: fallback) to details.avatarUrl else fallback to null

    /** Прочитал ли это кто-то *другой*. Свою квитанцию не считаем — иначе всё «прочитано» с открытия. */
    private fun hasForeignReceipt(event: EventTimelineItem, ownUserId: String) = event.readReceipts.keys.any { it != ownUserId }

    /** Доля ушедшего; null, пока делить не на что. */
    internal fun fraction(current: Long, total: Long): Double? = if (total > 0) minOf(1.0, current.toDouble() / total) else null

    /** Докуда вложение своего ушло. Null, пока у SDK нет общего, на что делить: кольцо с выдуманного нуля, что потом прыгнет, хуже того, что появляется с правдой. */
    private fun uploadProgress(state: EventSendState?): Double? {
        val upload = (state as? EventSendState.NotSentYet)?.progress ?: return null
        return fraction(upload.progress.current.toLong(), upload.progress.total.toLong())
    }

    private fun delivery(event: EventTimelineItem, ownUserId: String): DeliveryStatus = when (val state = event.localSendState) {
        is EventSendState.NotSentYet -> DeliveryStatus.Sending
        // Восстановимый сбой — SDK приостановил очередь комнаты, а не бросил сообщение: оно уйдёт само,
        // и рисовать его ошибкой — то, чего Telegram не делает никогда.
        is EventSendState.SendingFailed -> if (state.isRecoverable) DeliveryStatus.Sending else DeliveryStatus.Failed(reason(state.error))
        else -> if (hasForeignReceipt(event, ownUserId)) DeliveryStatus.Read else DeliveryStatus.Sent
    }

    /** Самая ранняя чужая квитанция; квитанция без времени не придумывает «сейчас». */
    private fun readAt(event: EventTimelineItem, ownUserId: String): Instant? =
        event.readReceipts.filterKeys { it != ownUserId }.values.mapNotNull { it.timestamp }.minOrNull()?.let(MessagePreview::instant)

    private fun reason(error: QueueWedgeError): SendFailure = when (error) {
        is QueueWedgeError.InsecureDevices -> SendFailure.INSECURE_DEVICES
        is QueueWedgeError.IdentityViolations -> SendFailure.IDENTITY_CHANGED
        is QueueWedgeError.CrossVerificationRequired -> SendFailure.VERIFICATION_REQUIRED
        is QueueWedgeError.MissingMediaContent -> SendFailure.MISSING_MEDIA
        is QueueWedgeError.InvalidMimeType -> SendFailure.UNSUPPORTED_FILE_TYPE
        is QueueWedgeError.GenericApiError -> SendFailure.SERVER
    }
}
