package wtf.iskra.features.widget

import android.content.Context
import android.graphics.BitmapFactory
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CancellationException
import wtf.iskra.core.media.MediaLoader
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.design.blobatar.BlobatarBitmap

/**
 * Кладёт снимок для виджета «Избранные чаты» и просит систему перерисовать его — только когда нарисованное изменилось
 * ([WidgetChats.fingerprint]). Лица — 192 px у серверного ресайзера, по одному, не больше восьми, и только после того, как
 * список успокоился (вызывающий ждёт).
 */
class WidgetPublisher(private val context: Context) {
    private val store = WidgetChats(context)

    suspend fun publish(accountId: String, rooms: List<RoomSummary>, loader: MediaLoader?) {
        val picked = WidgetChats.pick(rooms)
        val chats = picked.map { WidgetChat(it.id, accountId, it.name.ifEmpty { it.id }, it.unreadMessageCount > 0 || it.isMarkedUnread) }
        val print = WidgetChats.fingerprint(chats, picked.map { it.avatarUrl })
        if (print == store.fingerprint()) return
        val faces = chats.zip(picked).associate { (chat, room) ->
            val photo = room.avatarUrl?.let { url ->
                try {
                    loader?.avatar(url, FACE)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            }
            chat to WidgetChats.circle(photo ?: BlobatarBitmap.render(room.avatarSeed, FACE), FACE)
        }
        store.save(chats, faces, print)
        redraw()
    }

    /** Выход из аккаунта: чужие лица и имена с экрана блокировки не остаются. */
    suspend fun forget() {
        store.clear()
        redraw()
    }

    suspend fun redraw() {
        runCatching { FavouritesWidget().updateAll(context) }
    }

    private companion object {
        const val FACE = 192
    }
}
