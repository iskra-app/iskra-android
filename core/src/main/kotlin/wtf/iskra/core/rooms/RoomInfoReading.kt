package wtf.iskra.core.rooms

import wtf.iskra.core.R
import wtf.iskra.core.support.Strings
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RoomHero
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import uniffi.matrix_sdk_base.EncryptionState

/*
 * Комната так, как её видят экраны. Internal: `RoomInfo` — тип SDK, и выше `:core` его никто не
 * видит. Порт расширений `RoomInfo` из `RoomKind.swift`.
 */

internal fun RoomMembership.Companion.of(membership: Membership): RoomMembership? = when (membership) {
    Membership.JOINED -> RoomMembership.JOINED
    Membership.INVITED -> RoomMembership.INVITED
    // Отфильтровано `nonLeft` или поток, для которого нет экрана. Угадывать хуже, чем выбросить.
    else -> null
}

internal fun PrivacyStatus.Companion.of(state: EncryptionState): PrivacyStatus = when (state) {
    EncryptionState.ENCRYPTED -> PrivacyStatus.PROTECTED
    EncryptionState.NOT_ENCRYPTED -> PrivacyStatus.NOT_PROTECTED
    EncryptionState.UNKNOWN -> PrivacyStatus.UNKNOWN
}

/**
 * Порядок важен: пространство — тоже комната, и спрашивать его, личное оно или групповое, — вопрос
 * без правильного ответа. Поэтому оно решается первым и до двух других не доходит.
 */
internal val RoomInfo.kind: RoomKind
    get() = when {
        isSpace -> RoomKind.SPACE
        isDirect -> RoomKind.DIRECT
        else -> RoomKind.GROUP
    }

/**
 * Собеседник, как его описывает сервер. Герои обычно не включают самого пользователя — обычно, не
 * всегда, а в чате с одним собой есть только ты. Фильтр не даёт твоему лицу и имени оказаться в
 * разговоре, который должен быть с кем-то другим.
 */
internal fun RoomInfo.counterpart(besides: String?): RoomHero? = heroes.firstOrNull { it.userId != besides }

/**
 * Как назвать комнату. Комната без имени в Matrix — норма (у большинства личных чатов его нет),
 * поэтому имя падает на того, кто в ней, а не на сырой ID комнаты. Локальные имена (`LocalNames`)
 * лягут поверх — с этапом 8.
 */
internal fun RoomInfo.title(besides: String?, strings: Strings): String =
    displayName?.takeIf { it.isNotBlank() }
        ?: counterpart(besides)?.displayName
        ?: heroes.firstOrNull()?.displayName
        ?: strings[R.string.room_untitled]

/**
 * Картинка комнаты. В личном чате — картинка собеседника: у разговора с Аней ждут лица Ани. В
 * группе — **нет**: фотография одного героя подписала бы группу лицом того, кто оказался первым.
 */
internal fun RoomInfo.pictureUrl(besides: String?): String? {
    avatarUrl?.takeIf { it.isNotEmpty() }?.let { return it }
    if (kind != RoomKind.DIRECT) return null
    return counterpart(besides)?.avatarUrl
}

/**
 * Люди, которыми сервер описывает комнату, без самого пользователя. Трое: четвёртый в круге — пятно;
 * в порядке сервера: кластер, который перемешивается между синками, выглядит как сбой.
 */
internal fun RoomInfo.faces(besides: String?): List<RoomFace> =
    heroes.asSequence()
        .filter { it.userId != besides }
        .take(MAX_FACES)
        .map { RoomFace(id = it.userId, name = it.displayName ?: it.userId, avatarUrl = it.avatarUrl) }
        .toList()

private const val MAX_FACES = 3

/**
 * Из чего растёт сгенерированное лицо. В личном чате — из ID собеседника: то же существо, что у
 * его сообщений внутри и во всех других чатах. Из ID комнаты у одного человека было бы своё лицо в
 * каждом разговоре — противоположность тому, зачем лицо.
 */
internal fun RoomInfo.avatarSeed(besides: String?): String {
    if (kind != RoomKind.DIRECT) return id
    return counterpart(besides)?.userId ?: id
}

/** Последнее сообщение чата, уже сказанное словами, — что строка покажет под именем. */
internal data class LastMessage(
    val text: String?,
    val activity: java.time.Instant?,
    val kind: PreviewKind?,
    val mark: LastMessageMark?,
    val sender: String?,
)

/**
 * Строка списка из того, что сказал SDK. Null — комнате не место в списке (не вошёл и не позван):
 * покинутая или забаненная комната, нарисованная как обычный чат, — ровно тот класс ошибок, ради
 * конца которого этот тип и заведён.
 */
internal fun RoomSummary.Companion.of(
    info: RoomInfo,
    ownUserId: String?,
    last: LastMessage?,
    isMuted: Boolean,
    draft: String?,
    strings: Strings,
): RoomSummary? {
    val membership = RoomMembership.of(info.membership) ?: return null
    val kind = info.kind
    return RoomSummary(
        id = info.id,
        name = info.title(ownUserId, strings),
        avatarUrl = info.pictureUrl(ownUserId),
        avatarSeed = info.avatarSeed(ownUserId),
        // Кластер собирает только группа: у личного чата — картинка собеседника, у пространства — полка комнат.
        faces = if (kind == RoomKind.GROUP) info.faces(ownUserId) else emptyList(),
        kind = kind,
        privacy = PrivacyStatus.of(info.encryptionState),
        // `numUnread*`, а не `notificationCount`/`highlightCount`: под sliding sync сервер вторую пару
        // не присылает, и она навсегда ноль. `numUnread*` SDK считает сам по квитанциям — их же
        // читает Element X.
        unreadCount = info.numUnreadNotifications.toCount(),
        unreadMessageCount = info.numUnreadMessages.toCount(),
        mentionCount = info.numUnreadMentions.toCount(),
        lastMessage = last?.text,
        lastActivity = last?.activity,
        lastMessageKind = last?.kind,
        lastMessageMark = last?.mark,
        draft = draft,
        lastSender = last?.sender,
        isFavourite = info.isFavourite,
        isLowPriority = info.isLowPriority,
        isMarkedUnread = info.isMarkedUnread,
        // Два источника, и чат беззвучен, если так говорит любой. Набор списка — надёжная половина;
        // кэш SDK пуст на холодном старте, но первым узнаёт о заглушении с экрана чата.
        isMuted = isMuted || info.cachedUserDefinedNotificationMode == RoomNotificationMode.MUTE,
        membership = membership,
        invitedBy = info.inviter?.let { it.displayName ?: it.userId },
    )
}

private fun ULong.toCount(): Int = if (this > Int.MAX_VALUE.toULong()) Int.MAX_VALUE else toInt()
