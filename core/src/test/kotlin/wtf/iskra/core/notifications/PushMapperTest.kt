package wtf.iskra.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.matrix.rustcomponents.sdk.ImageMessageContent
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationRoomInfo
import org.matrix.rustcomponents.sdk.NotificationSenderInfo
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.TimelineEvent
import org.matrix.rustcomponents.sdk.TimelineEventContent
import wtf.iskra.core.R
import wtf.iskra.core.support.ResourceStrings

/**
 * Что пуш скажет к тому моменту, как дойдёт до экрана блокировки. Порт `PushTests.swift`.
 *
 * [PushDecoder] здесь не проверить — он открывает зашифрованный стор и ходит в сеть. [PushMapper] —
 * половина, которая решает каждое *слово*, и она чистая функция значения SDK.
 */
class PushMapperTest {
    private val strings = ResourceStrings()

    private fun room(name: String, isDm: Boolean = false, avatarUrl: String? = null) = NotificationRoomInfo(
        displayName = name,
        avatarUrl = avatarUrl,
        canonicalAlias = null,
        topic = null,
        joinRule = null,
        joinedMembersCount = 2uL,
        activeServiceMembersCount = 0uL,
        serviceMembers = emptyList(),
        isEncrypted = true,
        isDirect = isDm,
        isSpace = false,
        isDm = isDm,
    )

    private fun sender(name: String?, avatarUrl: String? = null) = NotificationSenderInfo(name, avatarUrl, false)

    private fun item(event: NotificationEvent, room: NotificationRoomInfo, sender: NotificationSenderInfo, isNoisy: Boolean = false) =
        NotificationItem(event, "{}", sender, room, isNoisy, false, null, null)

    /** Событие ленты без нативной половины: SDK оставляет для тестов конструктор с `NoHandle`. */
    private fun timeline(content: MessageLikeEventContent, senderId: String = "@anya:matrix.org") =
        NotificationEvent.Timeline(
            object : TimelineEvent(NoHandle) {
                override fun content() = TimelineEventContent.MessageLike(content)
                override fun senderId() = senderId
            },
        )

    /** Источник картинки без нативной половины: подписи «Фото» до файла дела нет. */
    private object FakeMediaSource : MediaSource(NoHandle)

    private fun text(body: String) = MessageLikeEventContent.RoomMessage(MessageType.Text(TextMessageContent(body, null)), null)

    private fun shownBy(outcome: PushOutcome): PushNotification {
        assertTrue("ожидали Show, а пришло $outcome", outcome is PushOutcome.Show)
        return (outcome as PushOutcome.Show).notification
    }

    // От кого

    @Test
    fun directChatNamesThePersonOnce() {
        assertEquals("Аня" to null, PushMapper.attribution("Аня", "Аня", isDirect = true))
        assertEquals("Кухня" to "Аня", PushMapper.attribution("Кухня", "Аня", isDirect = false))
    }

    @Test
    fun namelessSenderDoesNotLeaveABlankLine() {
        assertEquals("Кухня" to null, PushMapper.attribution("Кухня", "", isDirect = false))
    }

    /** Найдено запуском, не размышлением: приветственная комната matrix.org не `isDm`, но названа как пишущий. */
    @Test
    fun aRoomNamedAfterItsSenderSaysTheNameOnce() {
        val name = "Matrix.org (Official Account)"
        assertEquals(name to null, PushMapper.attribution(name, name, isDirect = false))
    }

    // Слова

    /** Фото — «Фото» и в списке, и в пуше: одно предложение, написанное один раз. */
    @Test
    fun attachmentsBorrowTheChatListsWords() {
        val photo = MessageType.Image(ImageMessageContent("cat.jpg", null, null, FakeMediaSource, null))
        assertEquals(strings[R.string.preview_photo], PushMapper.text(MessageLikeEventContent.RoomMessage(photo, null), strings))
    }

    @Test
    fun textIsPassedThroughUntouched() {
        assertEquals("жду на кухне", PushMapper.text(text("жду на кухне"), strings))
    }

    @Test
    fun reactionsAreNews() {
        assertNotNull(PushMapper.text(MessageLikeEventContent.ReactionContent("$1"), strings))
    }

    @Test
    fun plumbingIsNotNews() {
        assertNull(PushMapper.text(MessageLikeEventContent.CallCandidates, strings))
        assertNull(PushMapper.text(MessageLikeEventContent.KeyVerificationDone, strings))
    }

    @Test
    fun anUnreadableMessageStillSaysSo() {
        val words = PushMapper.text(MessageLikeEventContent.RoomEncrypted, strings)
        assertEquals(strings[R.string.preview_unreadable], words)
        assertFalse(words.isNullOrEmpty())
    }

    // Приглашения

    @Test
    fun invitationsAreASentence() {
        val invite = item(NotificationEvent.Invite("@anya:matrix.org"), room("Кухня"), sender("Аня"))
        val notification = shownBy(PushMapper.outcome(invite, "!room:matrix.org", "\$event", strings))

        assertEquals("Кухня", notification.title)
        // Предложение уже называет человека — строка отправителя была бы тем же именем второй раз.
        assertNull(notification.sender)
        assertTrue(notification.body.contains("Аня"))

        // По-русски без рода, нарочно: Matrix не знает ничьего пола, и «приглашает» — одно слово для всех.
        assertEquals("Аня приглашает вас в чат", ResourceStrings("values-ru").format(R.string.push_invite, "Аня"))
        assertEquals("Аня invites you to the chat", strings.format(R.string.push_invite, "Аня"))
    }

    @Test
    fun anonymousInvitationsFallBackToTheMatrixId() {
        val invite = item(NotificationEvent.Invite("@anya:matrix.org"), room("Кухня"), sender(null))
        val notification = shownBy(PushMapper.outcome(invite, "!room:matrix.org", "\$event", strings))
        assertTrue(notification.body.contains("@anya:matrix.org"))
    }

    @Test
    fun namelessRoomsAreStillTitled() {
        val invite = item(NotificationEvent.Invite("@anya:matrix.org"), room(""), sender("Аня"))
        val notification = shownBy(PushMapper.outcome(invite, "!room:matrix.org", "\$event", strings))
        assertEquals(strings[R.string.room_untitled], notification.title)
    }

    // Что показывается, а что нет

    @Test
    fun silenceAndFailureAreDifferentAnswers() {
        assertEquals(
            PushOutcome.Silence(SilenceReason.FILTERED_OUT),
            PushMapper.outcome(NotificationStatus.EventFilteredOut, "!r", "\$e", strings),
        )
        assertEquals(
            PushOutcome.Silence(SilenceReason.REDACTED),
            PushMapper.outcome(NotificationStatus.EventRedacted, "!r", "\$e", strings),
        )
        // Не тишина: на это уведомление встаёт заглушка.
        assertTrue(PushMapper.outcome(NotificationStatus.EventNotFound, "!r", "\$e", strings) is PushOutcome.Failed)
    }

    @Test
    fun loudnessComesFromThePushRules() {
        fun noisiness(isNoisy: Boolean): Boolean {
            val invite = item(NotificationEvent.Invite("@anya:matrix.org"), room("Кухня"), sender("Аня"), isNoisy)
            return shownBy(PushMapper.outcome(invite, "!r", "\$e", strings)).isNoisy
        }
        assertTrue(noisiness(true))
        assertFalse(noisiness(false))
    }

    // Своё, не из iOS: путь сообщения целиком, через событие SDK

    @Test
    fun aGroupMessageNamesTheRoomAndThePerson() {
        val message = item(timeline(text("привет")), room("Кухня"), sender("Аня", avatarUrl = "mxc://a/face"), isNoisy = true)
        val notification = shownBy(PushMapper.outcome(NotificationStatus.Event(message), "!r", "\$e", strings))

        assertEquals("Кухня", notification.title)
        assertEquals("Аня", notification.sender)
        assertEquals("привет", notification.body)
        assertEquals("Аня", notification.personName)
        assertEquals("@anya:matrix.org", notification.personId)
        assertEquals("mxc://a/face", notification.avatarUrl)
        assertTrue(notification.isGroup)
    }

    /** В личном чате второй строки нет, но человек у уведомления есть — и его лицо: картинка комнаты. */
    @Test
    fun aDirectMessageIsOnePersonWithAFace() {
        val message = item(timeline(text("привет")), room("Аня", isDm = true, avatarUrl = "mxc://a/room"), sender("Аня"))
        val notification = shownBy(PushMapper.outcome(NotificationStatus.Event(message), "!r", "\$e", strings))

        assertEquals("Аня", notification.title)
        assertNull(notification.sender)
        assertEquals("Аня", notification.personName)
        assertEquals("mxc://a/room", notification.avatarUrl)
        assertFalse(notification.isGroup)
    }

    @Test
    fun aCallNegotiationWakesNobody() {
        val message = item(timeline(MessageLikeEventContent.CallCandidates), room("Кухня"), sender("Аня"))
        assertEquals(
            PushOutcome.Silence(SilenceReason.NOT_WORTH_WAKING),
            PushMapper.outcome(NotificationStatus.Event(message), "!r", "\$e", strings),
        )
    }

    // Порт `LocalNamesTests`: прозвище доезжает до уведомления

    @Test
    fun pushUsesTheLocalName() {
        assertEquals("Мама" to null, PushMapper.attribution("anna_1987", "anna_1987", isDirect = true, localName = "Мама"))
        assertEquals("Кухня" to "Аня", PushMapper.attribution("Кухня", "Аня", isDirect = false, localName = null))
    }

    @Test
    fun pushPersonUsesTheLocalName() {
        assertEquals("Мама", PushMapper.speaker("anna_1987", "@anna:matrix.org", "Мама"))
        assertEquals("Аня", PushMapper.speaker("Аня", "@anna:matrix.org", null))
        assertEquals("@anna:matrix.org", PushMapper.speaker("", "@anna:matrix.org", null))
        assertEquals("Аня", PushMapper.speaker("Аня", "@anna:matrix.org", ""))
    }

    // Пока приложение заперто

    private fun shown() = PushOutcome.Show(PushNotification("!r", "\$e", "Кухня", "Аня", "привет", isNoisy = true))

    @Test
    fun unlockedShowsContent() {
        val notification = shownBy(PushMapper.redactIfLocked(shown(), locked = false, strings))
        assertEquals("привет", notification.body)
        assertEquals("Аня", notification.sender)
    }

    @Test
    fun lockedHidesContent() {
        val notification = shownBy(PushMapper.redactIfLocked(shown(), locked = true, strings))
        assertNull(notification.sender)
        assertFalse(notification.body.contains("привет"))
        assertEquals("Iskra", notification.title)
        // Куда вести — остаётся: нажатие после замка всё равно открывает нужный чат.
        assertEquals("!r", notification.roomId)
        assertEquals("\$e", notification.eventId)
        // И звук не теряется: запертый телефон всё равно звенит о громком.
        assertTrue(notification.isNoisy)
    }

    @Test
    fun lockedLeavesSilenceAlone() {
        val silence = PushOutcome.Silence(SilenceReason.REDACTED)
        assertEquals(silence, PushMapper.redactIfLocked(silence, locked = true, strings))
    }
}
