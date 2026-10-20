package wtf.iskra.core.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import wtf.iskra.core.session.AuthenticationService
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/**
 * «Ответить» и «Прочитано» прямо из уведомления — без экранов, часто без живого приложения. Клиент
 * берётся в аренду, как у [PushDecoder]: открыт сеанс — его, нет — восстановленный на время действия.
 */
class NotificationActions(private val authentication: AuthenticationService) {
    /**
     * Отправляет ответ и **ждёт**, пока он уйдёт: очередь SDK отправляет в фоне, а клиент, отпущенный
     * сразу после постановки в очередь, закрылся бы раньше, чем запрос вышел. Ушло — true; нет — false,
     * и уведомление говорит, что не ушло (сообщение остаётся в очереди SDK до следующего запуска).
     */
    suspend fun reply(accountId: String, roomId: String, text: String): Boolean = withClient(accountId) { client ->
        val room = client.getRoom(roomId) ?: return@withClient false
        try {
            room.enableSendQueue(true)
            val outcome = CompletableDeferred<Boolean>()
            val handle = room.subscribeToSendQueueUpdates(object : SendQueueListener {
                override fun onUpdate(update: RoomSendQueueUpdate) {
                    when (update) {
                        is RoomSendQueueUpdate.SentEvent -> outcome.complete(true)
                        is RoomSendQueueUpdate.SendError -> outcome.complete(false)
                        else -> Unit
                    }
                }
            })
            try {
                val timeline = room.timeline()
                try {
                    // Не пишется в журнал и никуда, кроме зашифрованной очереди SDK.
                    timeline.send(messageEventContentFromMarkdown(text)).destroy()
                    withTimeLimit(SEND_LIMIT) { outcome.await() }
                } finally {
                    timeline.close()
                }
            } finally {
                handle.cancel()
            }
        } finally {
            room.close()
        }
    }

    /** Отметить чат прочитанным — тем же путём, что «Прочитано» в списке чатов. */
    suspend fun markRead(accountId: String, roomId: String): Boolean = withClient(accountId) { client ->
        val room = client.getRoom(roomId) ?: return@withClient false
        try {
            withTimeLimit(READ_LIMIT) {
                room.markAsRead(ReceiptType.READ)
                room.setUnreadFlag(false)
            }
            true
        } finally {
            room.close()
        }
    }

    private suspend fun withClient(accountId: String, action: suspend (org.matrix.rustcomponents.sdk.Client) -> Boolean): Boolean {
        val client = try {
            authentication.leaseClient(accountId) ?: return false
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "the session would not open for a notification action: ${Log.describe(error)}")
            return false
        }
        return try {
            action(client)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "a notification action failed: ${Log.describe(error)}")
            false
        } finally {
            authentication.releaseClient(accountId, client)
        }
    }

    private companion object {
        /** Медленная сеть успевает; молчащий сервер не держит receiver дольше, чем система разрешит. */
        val SEND_LIMIT = 20.seconds
        val READ_LIMIT = 10.seconds
    }
}
