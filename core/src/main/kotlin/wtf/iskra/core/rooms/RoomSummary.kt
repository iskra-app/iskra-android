package wtf.iskra.core.rooms

import wtf.iskra.core.R
import java.time.Instant

/**
 * Состоишь ли ты в этом чате или тебя только зовут. Порт `RoomMembership`.
 *
 * Фильтр SDK — `nonLeft`, и это правильно: неотвеченное приглашение — не покинутая комната. Поэтому
 * приглашённая комната приходит в список, выглядя ровно как обычная, — и без этого типа ничто не
 * сказало бы «вас позвали». Только два состояния, в которых комната может быть в списке:
 * `left` и `banned` отфильтрованы раньше, а `knocked` — дверь, в которую это приложение не стучит.
 */
enum class RoomMembership {
    JOINED,
    INVITED,
    ;

    companion object
}

/**
 * Защищена ли переписка — словами, а не протоколом. Экран никогда не показывает «E2EE» или
 * «Megolm»: замок и предложение. Этот тип — шов между словарём протокола и словарём человека.
 */
enum class PrivacyStatus(val explanation: Int) {
    /** Прочесть могут только участники. */
    PROTECTED(R.string.privacy_protected),

    /** Сервер может прочесть. Так у открытых комнат и старых нешифрованных. */
    NOT_PROTECTED(R.string.privacy_not_protected),

    /** Ещё не знаем. Никогда не обещать защиту, которой не видим. */
    UNKNOWN(R.string.privacy_unknown),
    ;

    companion object
}

/**
 * Что комната *есть*: разговор с одним человеком, с несколькими или полка для других комнат.
 * Говорит, как комната *сделана*, а не сколько в ней людей сейчас: группа из двоих — группа, личный
 * чат, куда позвали третьего, — всё ещё личный. Порт `RoomKind.swift`.
 */
enum class RoomKind { SPACE, DIRECT, GROUP }

/**
 * Один человек — столько, сколько нужно строке списка. Вынут из `RoomHero` SDK: герои приходят даром
 * вместе с `RoomInfo`, а список участников стоил бы вызова на строку.
 */
data class RoomFace(
    /** Matrix ID: из него растёт сгенерированное лицо, поэтому это идентификатор, а не имя. */
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
)

/** Каким вложением было последнее сообщение — значок перед «Фото», «Голосовое» и прочим. */
enum class PreviewKind { PHOTO, VIDEO, VIDEO_NOTE, VOICE, AUDIO, FILE, LOCATION, STICKER, POLL }

/** Куда дошло твоё последнее сообщение — отметка перед ним в списке. */
enum class LastMessageMark { SENDING, SENT, READ, FAILED }

/**
 * Насколько громко значок говорит о непрочитанном. Цвета здесь нет: это `:core`, и только строка
 * списка знает, какого цвета акцент. Упоминание — не цвет, а «@» *рядом* со значком
 * ([RoomSummary.isMentioned]), как на iOS.
 */
enum class UnreadEmphasis {
    /** Обычное непрочитанное — цветом, который выбрал человек. */
    ORDINARY,

    /** Чат, заглушённый по имени. Серый — и серый здесь не отсутствие цвета, а «ты сам просил не говорить». */
    MUTED,
}

/**
 * Строка в списке чатов. Снимок: дёшево сравнить, безопасно отдать в Compose. Порт `RoomSummary.swift`;
 * ни один тип SDK сюда не проникает.
 */
data class RoomSummary(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    /** Из чего растёт лицо без картинки: ID комнаты, а в личном чате — ID собеседника. */
    val avatarSeed: String = id,
    /** Несколько участников, описывающих группу. Пусто, если это не группа. */
    val faces: List<RoomFace> = emptyList(),
    val kind: RoomKind = RoomKind.GROUP,
    val privacy: PrivacyStatus = PrivacyStatus.PROTECTED,
    /** Непрочитанное, которое бы уведомило, — по настройкам уведомлений человека. */
    val unreadCount: Int = 0,
    /** Непрочитанные сообщения, что бы ни говорили настройки. У беззвучного чата вместо числа — точка. */
    val unreadMessageCount: Int = 0,
    val mentionCount: Int = 0,
    val lastMessage: String? = null,
    val lastActivity: Instant? = null,
    val lastMessageKind: PreviewKind? = null,
    /** Отметка своего последнего сообщения; null, когда последнее — чужое. */
    val lastMessageMark: LastMessageMark? = null,
    /** Начатое и не отправленное — показывается вместо последнего сообщения: «Черновик: …». */
    val draft: String? = null,
    /** Кто написал последнее, если не ты: строкой над текстом в группе, как в Telegram. */
    val lastSender: String? = null,
    /** Тег `m.favourite`: путешествует с аккаунтом, а не с устройством. */
    val isFavourite: Boolean = false,
    /** Тег `m.lowpriority` — ближайшее к архиву, что есть в протоколе. */
    val isLowPriority: Boolean = false,
    /** Помечен непрочитанным вручную. Не то же, что непрочитанные сообщения. */
    val isMarkedUnread: Boolean = false,
    /** Заглушён *по имени* — правилом на этот ID комнаты. Не «аккаунт тихий». */
    val isMuted: Boolean = false,
    val membership: RoomMembership = RoomMembership.JOINED,
    /** Кто позвал — имя или Matrix ID. Может не быть и у приглашения: события приглашения нет в сторе. */
    val invitedBy: String? = null,
) {
    val isSpace: Boolean get() = kind == RoomKind.SPACE
    val isDirectMessage: Boolean get() = kind == RoomKind.DIRECT

    /**
     * Непрочитанные реакции. Счёта у SDK нет — он выводится: реакция уведомляет (попадает в
     * [unreadCount]), но сообщением не считается (не попадает в [unreadMessageCount]). Ошибается в
     * сторону «меньше», никогда «больше».
     */
    val unreadReactionCount: Int get() = maxOf(0, unreadCount - unreadMessageCount)

    /** Непрочитанное, которое бы уведомило, без реакций. Это число на значке. */
    val unreadNotifyingMessages: Int get() = unreadCount - unreadReactionCount
    val hasUnreadReactions: Boolean get() = unreadReactionCount > 0

    /** Есть что читать — сообщения или флаг вручную. Одни реакции — не это. */
    val hasUnread: Boolean get() = unreadNotifyingMessages > 0 || unreadMessageCount > 0 || isMarkedUnread

    /** Есть число. У беззвучного или помеченного вручную — непрочитанное без числа, и у него точка. */
    val hasUnreadCount: Boolean get() = unreadNotifyingMessages > 0
    val isMentioned: Boolean get() = mentionCount > 0
    val unreadEmphasis: UnreadEmphasis get() = if (isMuted) UnreadEmphasis.MUTED else UnreadEmphasis.ORDINARY
    val isInvitation: Boolean get() = membership == RoomMembership.INVITED
    val isPinned: Boolean get() = isFavourite
    val isArchived: Boolean get() = isLowPriority

    companion object
}
