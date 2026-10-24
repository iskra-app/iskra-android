package wtf.iskra.features.room

import android.content.Context
import android.net.Uri
import wtf.iskra.core.media.MediaSettings
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.Log
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.caption
import java.io.File

/**
 * Возможность переслать, переданная вниз, чтобы меню сообщения могло отправить его в другую комнату, не будучи
 * привязанным ко всему сеансу. Единственный кусок интерфейса сообщения, что тянется через комнаты, — остальное в пузыре
 * про комнату, в которой он. Порт `Forwarding`.
 *
 * В Matrix нет настоящей пересылки: пересылка — новое сообщение, несущее то же самое, а у медиа это значит отправить
 * заново через шифрующий путь, а не скопировать адрес источника: чужой `MediaSource` отдал бы участникам другой комнаты
 * вложение, к которому у них нет ключей (или, хуже, загруженное открытым).
 */
class Forwarding(private val session: UserSession, private val context: Context) {
    /** Куда можно: чаты, в которых вы состоите. Пространство — не чат, приглашение — не комната. */
    fun destinations(): List<RoomSummary> = session.rooms.state.value.rooms.filter { !it.isInvitation && !it.isSpace }

    /** Отправить [message] в [roomId]. Ложь — только когда в ту комнату не попасть совсем. */
    suspend fun forward(message: Message, roomId: String): Boolean {
        val target = session.timeline(roomId) ?: return false
        return try {
            target.start()
            for (member in message.members) forwardOne(target, member)
            true
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.error(Log.Category.TIMELINE, "пересылка не удалась: ${Log.describe(error)}")
            false
        } finally {
            target.close()
        }
    }

    /** Слова, написанные под пересылкой, уходят следом отдельным сообщением: сначала что передаётся, потом что вы скажете о нём. */
    suspend fun comment(text: String, roomId: String) {
        val target = session.timeline(roomId) ?: return
        try {
            target.start()
            target.send(text)
        } finally {
            target.close()
        }
    }

    private suspend fun forwardOne(target: wtf.iskra.core.timeline.TimelineStore, message: Message) {
        val caption = message.kind.caption
        when (val kind = message.kind) {
            is MessageKind.Text -> target.send(kind.body)
            is MessageKind.Notice -> target.send(kind.body)
            is MessageKind.Emote -> target.send(kind.body)
            is MessageKind.Image -> target.sendPhoto(session.media.content(kind.attachment), kind.attachment.filename, MediaSettings.DEFAULT, caption)
            is MessageKind.Video -> {
                val file = session.media.file(kind.attachment, File(context.cacheDir, FORWARD_DIR))
                try {
                    target.sendVideo(context, Uri.fromFile(file), kind.attachment.filename, MediaSettings.DEFAULT, caption)
                } finally {
                    file.delete()
                }
            }
            is MessageKind.File -> target.sendFile(session.media.content(kind.attachment), kind.attachment.filename, kind.attachment.mimeType, caption)
            // Голосовое, кружок, место, стикер и всё, чего нет или что не отправить: ничего не уходит.
            else -> Unit
        }
    }

    private companion object {
        const val FORWARD_DIR = "forward"
    }
}
