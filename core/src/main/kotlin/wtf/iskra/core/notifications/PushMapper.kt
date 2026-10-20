package wtf.iskra.core.notifications

import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TimelineEventContent
import wtf.iskra.core.R
import wtf.iskra.core.rooms.MessagePreview
import wtf.iskra.core.support.Strings

/**
 * То, что SDK нашёл для пуша, → слова, которые человек прочтёт. Порт `PushMapper.swift`.
 *
 * То же деление, что у `TimelineMapper`, и по той же причине: [PushDecoder] открывает стор,
 * восстанавливает сессию и ходит в сеть — ничего из этого тест не может, — а *это*, решающее каждое
 * слово на экране блокировки, чистая функция значения SDK и закрепляется точно (`PushMapperTest`).
 */
internal object PushMapper {
    fun outcome(
        status: NotificationStatus,
        roomId: String,
        eventId: String,
        strings: Strings,
        localName: String? = null,
    ): PushOutcome = when (status) {
        is NotificationStatus.Event -> outcome(status.item, roomId, eventId, strings, localName)
        NotificationStatus.EventFilteredOut -> PushOutcome.Silence(SilenceReason.FILTERED_OUT)
        NotificationStatus.EventRedacted -> PushOutcome.Silence(SilenceReason.REDACTED)
        // Не тишина. Сервер сказал, что событие есть, и не смог его выдать, — оснований решить, что оно
        // неважно, у нас нет. Это сбой, и вместо проглоченного сообщения на экране остаётся заглушка.
        NotificationStatus.EventNotFound -> PushOutcome.Failed("the event could not be found")
    }

    /**
     * [localName] — имя, которое этому чату дали на этом устройстве (`LocalNames`, этап 8). Передаётся
     * явно, с аккаунтом пуша, а не читается из «текущего»: два аккаунта на одном телефоне.
     */
    fun outcome(
        item: NotificationItem,
        roomId: String,
        eventId: String,
        strings: Strings,
        localName: String? = null,
    ): PushOutcome {
        val senderName = item.senderInfo.displayName.orEmpty()
        val roomName = item.roomInfo.displayName.ifEmpty { strings[R.string.room_untitled] }
        return when (val event = item.event) {
            // Приглашение — единственный пуш не о сообщении, и единственный, где имя отправителя — само
            // предложение, а не подпись над ним. Не выбрал имени — остаётся Matrix ID: «@anya:matrix.org
            // приглашает вас» лучше пустоты.
            is NotificationEvent.Invite -> {
                val who = senderName.ifEmpty { event.sender }
                PushOutcome.Show(
                    PushNotification(
                        roomId = roomId,
                        eventId = eventId,
                        title = roomName,
                        sender = null,
                        body = strings.format(R.string.push_invite, who),
                        isNoisy = item.isNoisy ?: false,
                        personName = who,
                        personId = event.sender,
                        avatarUrl = picture(item),
                        isGroup = !item.roomInfo.isDm,
                        isInvitation = true,
                    ),
                )
            }
            is NotificationEvent.Timeline -> {
                val content = runCatching { event.event.content() }.getOrNull()
                val messageLike = (content as? TimelineEventContent.MessageLike)?.content
                message(
                    messageLike = messageLike,
                    senderId = event.event.senderId(),
                    senderName = senderName,
                    roomName = roomName,
                    isDirect = item.roomInfo.isDm,
                    isNoisy = item.isNoisy ?: false,
                    isMention = item.hasMention ?: false,
                    avatarUrl = picture(item),
                    roomId = roomId,
                    eventId = eventId,
                    strings = strings,
                    localName = localName,
                )
            }
        }
    }

    /** Сообщение ленты. Отдельно от [outcome], чтобы тест не собирал событие SDK целиком. */
    fun message(
        messageLike: MessageLikeEventContent?,
        senderId: String,
        senderName: String,
        roomName: String,
        isDirect: Boolean,
        isNoisy: Boolean,
        avatarUrl: String?,
        roomId: String,
        eventId: String,
        strings: Strings,
        localName: String? = null,
        isMention: Boolean = false,
    ): PushOutcome {
        val body = messageLike?.let { text(it, strings) } ?: return PushOutcome.Silence(SilenceReason.NOT_WORTH_WAKING)
        val (title, sender) = attribution(roomName, senderName, isDirect, localName)
        return PushOutcome.Show(
            PushNotification(
                roomId = roomId,
                eventId = eventId,
                title = title,
                sender = sender,
                body = body,
                isNoisy = isNoisy,
                personName = speaker(senderName, senderId, localName),
                personId = senderId,
                avatarUrl = avatarUrl,
                isGroup = sender != null,
                isMention = isMention,
            ),
        )
    }

    /**
     * Уведомление до «Новое сообщение», пока приложение заперто: остаётся только то, что нужно, чтобы
     * открыть нужный чат после замка. Приглашение тоже — «Аня приглашает вас» называет человека.
     * Тишина и сбой проходят как есть: прятать нечего. Блокировки на Android пока нет, правило — уже.
     */
    fun redactIfLocked(outcome: PushOutcome, locked: Boolean, strings: Strings): PushOutcome {
        if (!locked || outcome !is PushOutcome.Show) return outcome
        val notification = outcome.notification
        return PushOutcome.Show(
            PushNotification(
                roomId = notification.roomId,
                eventId = notification.eventId,
                title = strings[R.string.push_hidden_title],
                sender = null,
                body = strings[R.string.push_hidden_body],
                isNoisy = notification.isNoisy,
            ),
        )
    }

    /**
     * От кого уведомление — не совсем тот же вопрос, что кто его отправил.
     *
     * В группе две строки — комната и человек, и нужны обе. В личном чате имя комнаты уже *и есть* имя
     * человека, и строка отправителя напечатала бы его второй раз прямо под ним. Последнее правило
     * найдено, а не выведено: приветственная комната matrix.org не помечена `isDm`, но называется ровно
     * как пишущий в ней аккаунт, и уведомление выходило «Matrix.org (Official Account)» с тем же под ним.
     * Поэтому правило не «личный ли чат», а *не называть одно имя дважды*.
     */
    fun attribution(
        roomName: String,
        senderName: String,
        isDirect: Boolean,
        localName: String? = null,
    ): Pair<String, String?> {
        // Имя, данное на этом устройстве, побеждает и забирает вторую строку: оно бывает только у
        // личного чата, где отправитель и есть чат.
        if (!localName.isNullOrEmpty()) return localName to null
        // Безымянный отправитель — говорит комната: пустая вторая строка и повторенная — два способа ошибиться.
        if (senderName.isEmpty()) return roomName to null
        val title = if (isDirect) senderName else roomName
        return title to senderName.takeIf { it != title }
    }

    /**
     * Имя человека на уведомлении (`Person` в `MessagingStyle`) — **не** [attribution]: в личном чате
     * система рисует разговор именем собеседника, а не заголовком, и прозвище, которого нет здесь, не
     * дошло бы до единственного экрана, где его не исправить. Прозвище, иначе выбранное имя, иначе
     * Matrix ID — голый `@anya:matrix.org` говорит больше пустоты.
     */
    fun speaker(senderName: String, senderId: String, localName: String?): String {
        if (!localName.isNullOrEmpty()) return localName
        return senderName.ifEmpty { senderId }
    }

    /**
     * Чьё лицо на уведомлении: отправителя, а нет его — комнаты. Многие не ставят картинку, а в личном
     * чате картинка комнаты *и есть* собеседник: Matrix собирает её из второго участника.
     */
    fun picture(item: NotificationItem): String? =
        item.senderInfo.avatarUrl?.takeIf { it.isNotEmpty() } ?: item.roomInfo.avatarUrl?.takeIf { it.isNotEmpty() }

    /** Слова одного события. Null — «не новость» (см. [SilenceReason.NOT_WORTH_WAKING]). */
    fun text(content: MessageLikeEventContent, strings: Strings): String? = when (content) {
        // То самое предложение, которое написал бы список чатов: фото не может быть «Фото» в одном месте
        // и «Изображением» в другом.
        is MessageLikeEventContent.RoomMessage -> MessagePreview.text(content.messageType, strings)
        is MessageLikeEventContent.Poll -> strings[R.string.preview_poll]
        MessageLikeEventContent.Sticker -> strings[R.string.preview_sticker]
        // Сюда доходит только реакция на написанное читателем — остальные [PushDecoder] глушит раньше.
        // Какой эмодзи — в событии, которое отдаёт SDK, нет, и здесь его нет.
        is MessageLikeEventContent.ReactionContent -> strings[R.string.push_reaction]
        // Ключ к этому сообщению до устройства не дошёл. Сказать об этом лучше тишины: что-то сказано.
        MessageLikeEventContent.RoomEncrypted -> strings[R.string.preview_unreadable]
        // Переговоры звонка, шаги подтверждения, удаления: события, но не новости.
        else -> null
    }
}
