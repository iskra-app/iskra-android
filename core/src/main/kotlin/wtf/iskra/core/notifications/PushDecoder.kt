package wtf.iskra.core.notifications

import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.TimelineEventContent
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.TimeoutError
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Два идентификатора из пуша → предложение, которое человек прочтёт. Порт `PushDecoder.swift`.
 *
 * Шлюз — водопровод: он приносит ID комнаты и события и не знает, что они значат. *Это* — часть, которая
 * открывает зашифрованный стор, находит событие, расшифровывает его ключами Megolm и укладывается в
 * бюджет, который система даёт пушу.
 *
 * Обработчик пуша на Android живёт **в процессе приложения**. Поэтому клиент берётся в аренду у
 * [AuthenticationService]: открыт сеанс — тот же клиент, нет — собранный заново, и если человек откроет
 * приложение, пока пуш расшифровывает, восстановление получит этот же. Два `Client` на один SQLite в
 * одном процессе не бывает никогда.
 */
class PushDecoder(
    private val authentication: AuthenticationService,
    private val strings: Strings,
) {
    /**
     * Всё, что пуш Matrix на самом деле несёт, — в то, что читают. Никогда не бросает: у обработчика
     * одна обязанность — показать что-нибудь, и ошибка, вылетевшая отсюда, — ошибка, которую на
     * единственном пути, где забыть значит остаться без уведомления, надо не забыть поймать.
     *
     * @param accountId чей пуш (`iskra_account`). Null — pusher старше этого ключа: тогда пробуются
     *   аккаунт на экране и остальные после него, пока у кого-то не найдётся событие.
     * @param budget на всё сразу, с восстановлением сеанса, а не только на последний шаг.
     */
    suspend fun notification(roomId: String, eventId: String, accountId: String?, budget: Duration = BUDGET): PushOutcome =
        try {
            withTimeLimit(budget) { decrypt(roomId, eventId, accountId) }
        } catch (_: TimeoutError) {
            Log.error(Log.Category.PUSH, "the push did not decrypt inside $budget")
            PushOutcome.Failed("timed out after $budget", timedOut = true)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "the push would not decrypt: ${Log.describe(error)}")
            PushOutcome.Failed(Log.describe(error))
        }

    /**
     * Как назвать аккаунт пуша, когда их на телефоне несколько и читающему надо сказать, кому пришло.
     * Null при одном: тогда это без слов понятно, а строка «всё равно» — шум на каждом уведомлении.
     */
    fun accountLabel(accountId: String?): String? {
        accountId ?: return null
        val accounts = authentication.accounts()
        if (accounts.size < 2) return null
        val account = accounts.firstOrNull { it.sessionId == accountId } ?: return null
        return account.displayName?.takeIf { it.isNotEmpty() } ?: account.userId
    }

    private suspend fun decrypt(roomId: String, eventId: String, accountId: String?): PushOutcome {
        val candidates = candidates(accountId)
        if (candidates.isEmpty()) return PushOutcome.NotSignedIn

        for ((index, candidate) in candidates.withIndex()) {
            val isLast = index == candidates.lastIndex
            val client = try {
                authentication.leaseClient(candidate)
            } catch (error: Throwable) {
                if (error is CancellationException || error is TimeoutError) throw error
                Log.error(Log.Category.PUSH, "the session would not open for a push: ${Log.describe(error)}")
                if (isLast) return PushOutcome.Failed(Log.describe(error))
                continue
            }
            if (client == null) {
                if (isLast) return PushOutcome.NotSignedIn
                continue
            }
            try {
                val outcome = decrypt(client, candidate, roomId, eventId, isLast) ?: continue
                return outcome
            } finally {
                authentication.releaseClient(candidate, client)
            }
        }
        return PushOutcome.NotSignedIn
    }

    /** Null — у этого аккаунта события нет, и есть кого спросить следующим. */
    private suspend fun decrypt(client: Client, account: String, roomId: String, eventId: String, isLast: Boolean): PushOutcome? {
        // Цикл открытого сеанса, если он есть: расшифровка и синхронизация делят одно разрешение на
        // шифрованную синхронизацию. Нет — свой, не запущенный, на одну эту расшифровку.
        val running = authentication.openSession(account)?.rooms?.runningSyncService
        val own: SyncService? = if (running == null) client.syncService().finish() else null
        val notifications = client.notificationClient(NotificationProcessSetup.SingleProcess(running ?: own!!))
        try {
            val status = notifications.getNotification(roomId, eventId)
            try {
                // Не этого аккаунта событие — бывает, только когда пуш не сказал, чей он.
                if (status is NotificationStatus.EventNotFound && !isLast) return null
                return present(status, roomId, eventId, account, client, notifications)
            } finally {
                status.destroy()
            }
        } finally {
            notifications.close()
            own?.close()
        }
    }

    /** Чьи сторы открывать, в каком порядке. */
    private fun candidates(accountId: String?): List<String> {
        if (accountId != null) return listOf(accountId)
        val active = authentication.activeAccountId()
        val everybody = authentication.accounts().map { it.sessionId }
        return listOfNotNull(active) + everybody.filter { it != active }
    }

    private suspend fun present(
        status: NotificationStatus,
        roomId: String,
        eventId: String,
        account: String,
        client: Client,
        notifications: NotificationClient,
    ): PushOutcome {
        // Реакция на чужое сообщение — не новость этого читателя. Здесь, а не в маппере: ответ требует
        // второго события и второго похода, а маппер — чистая функция одного.
        if (status is NotificationStatus.Event && isSomebodyElsesBusiness(status.item, roomId, client, notifications)) {
            return PushOutcome.Silence(SilenceReason.NOT_WORTH_WAKING)
        }
        // Блокировки приложения на Android пока нет; правило [PushMapper.redactIfLocked] уже есть.
        // Прозвища — того аккаунта, чей пуш, явно: два аккаунта расшифровывают бок о бок.
        val localName = authentication.localNamesOf(account)?.name(roomId)
        val outcome = PushMapper.outcome(status, roomId, eventId, strings, localName)
        if (outcome !is PushOutcome.Show) return outcome
        val notification = outcome.notification
        return PushOutcome.Show(notification.copy(avatar = avatar(notification, client), accountId = account))
    }

    /**
     * Реакция ли это на сообщение, которое написал не читатель. В группе из тридцати человек каждый
     * палец вверх под каждым чужим сообщением — способ научить выключить уведомления совсем. Сервер
     * различить не может — правило пушей не видит, на чьё сообщение реакция, — поэтому здесь.
     */
    private suspend fun isSomebodyElsesBusiness(
        item: NotificationItem,
        roomId: String,
        client: Client,
        notifications: NotificationClient,
    ): Boolean {
        val event = (item.event as? NotificationEvent.Timeline)?.event ?: return false
        val content = runCatching { event.content() }.getOrNull() as? TimelineEventContent.MessageLike ?: return false
        val reaction = content.content as? MessageLikeEventContent.ReactionContent ?: return false
        return try {
            val related = withTimeLimit(RELATED_EVENT_BUDGET) { notifications.getNotification(roomId, reaction.relatedEventId) }
            try {
                val relatedEvent = ((related as? NotificationStatus.Event)?.item?.event as? NotificationEvent.Timeline)?.event
                // Сообщение, на которое реакция, не прочиталось. Показываем: реакция, которая *может*
                // быть на твоё, ближе к новости, чем к шуму.
                relatedEvent != null && relatedEvent.senderId() != client.userId()
            } finally {
                related.destroy()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "the reacted-to message could not be read: ${Log.describe(error)}")
            false
        }
    }

    /**
     * Лицо отправителя, или null. Ограничено дважды, и оба предела — суть: лицо стоит двух секунд и ни
     * одной больше (альтернатива медленному аватару — *никакого* уведомления), а байты — потолком:
     * адрес указывает на сервер, который может прислать что угодно. Любая неудача — тихий null.
     */
    private suspend fun avatar(notification: PushNotification, client: Client): ByteArray? {
        val url = avatarUrl(notification, client) ?: return null
        return try {
            val bytes = withTimeLimit(AVATAR_BUDGET) {
                val source = MediaSource.fromUrl(url)
                try {
                    // Ресайзер сервера, как у `MediaLoader.avatar`: аватар публичен по построению.
                    client.getMediaThumbnail(source, AVATAR_SIZE, AVATAR_SIZE)
                } finally {
                    source.destroy()
                }
            }
            if (bytes.size > AVATAR_LIMIT) {
                Log.error(Log.Category.PUSH, "the sender's avatar was too large to put on a notification")
                null
            } else {
                bytes
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "the sender's avatar could not be fetched: ${Log.describe(error)}")
            null
        }
    }

    /**
     * Откуда взять картинку. Само уведомление почти никогда её не несёт — клиент уведомлений
     * синхронизирует минимум, — поэтому второй источник — профиль: один маленький запрос к серверу,
     * с которым клиент и так говорит.
     */
    private suspend fun avatarUrl(notification: PushNotification, client: Client): String? {
        notification.avatarUrl?.takeIf { it.isNotEmpty() }?.let { return it }
        val personId = notification.personId ?: return null
        return try {
            withTimeLimit(PROFILE_BUDGET) { client.getProfile(personId) }.avatarUrl?.takeIf { it.isNotEmpty() }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "the sender's profile could not be read: ${Log.describe(error)}")
            null
        }
    }

    companion object {
        /**
         * На всё сразу, с восстановлением сеанса, — когда ждать можно: повтор из фоновой задачи. Транспорт
         * сам даёт меньше ([INLINE_BUDGET]).
         */
        val BUDGET = 20.seconds

        /**
         * Сколько ждать прямо в обработчике транспорта: receiver и сервис FCM живут около десяти секунд,
         * и уведомление должно появиться раньше, чем система решит, что приложение зависло.
         */
        val INLINE_BUDGET = 8.seconds

        /** Второй поход ради, в лучшем случае, сердечка — маленькая доля, и тихий отказ. */
        private val RELATED_EVENT_BUDGET = 2.seconds
        private val PROFILE_BUDGET = 1.seconds
        private val AVATAR_BUDGET = 2.seconds

        /** 2 МБ: аватар — маленький квадрат, и ничто разумное не больше. */
        private const val AVATAR_LIMIT = 2 * 1024 * 1024
        private const val AVATAR_SIZE = 256uL
    }
}
