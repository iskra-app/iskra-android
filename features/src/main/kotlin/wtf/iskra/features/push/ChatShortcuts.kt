package wtf.iskra.features.push

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CancellationException
import wtf.iskra.core.media.MediaLoader
import wtf.iskra.core.rooms.RoomSummary
import wtf.iskra.design.blobatar.BlobatarBitmap

/**
 * Ярлыки недавних чатов: долгое нажатие на иконку и «Поделиться» (Direct Share) — те же долгоживущие ярлыки разговоров, что
 * заводят уведомления (`ShortcutIds`). Лицо — фото чата, если оно приехало (192 px у серверного ресайзера), иначе существо
 * из ID комнаты. Нажатие открывает чат так же, как уведомление (`PushRouter`).
 */
class ChatShortcuts(private val context: Context) {
    private var published: List<Key> = emptyList()

    private data class Key(val id: String, val name: String, val avatarUrl: String?)

    /** Верх списка — столько, сколько лаунчер покажет; закреплённые уже идут выше в самом списке. */
    suspend fun publish(accountId: String, rooms: List<RoomSummary>, loader: MediaLoader?) {
        val limit = minOf(MAX, runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(MAX))
        val top = rooms.take(limit)
        val keys = top.map { Key(it.id, it.name, it.avatarUrl) }
        if (keys == published) return
        for (room in top) {
            val id = ShortcutIds.room(accountId, room.id)
            val photo = room.avatarUrl?.let { url ->
                try {
                    loader?.avatar(url, FACE_SIZE)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    null
                }
            }
            val face: Bitmap = photo ?: BlobatarBitmap.render(room.avatarSeed, FACE_SIZE)
            val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
                .setAction(Intent.ACTION_VIEW)
                .putExtra(PushIntents.EXTRA_ROOM, room.id)
                .putExtra(PushIntents.EXTRA_ACCOUNT, accountId)
            val shortcut = ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(room.name.ifEmpty { room.id })
                .setLongLived(true)
                .setIcon(IconCompat.createWithAdaptiveBitmap(face))
                .setIntent(intent)
                .setLocusId(LocusIdCompat(id))
                .setCategories(setOf(CATEGORY))
                .apply { if (room.kind == wtf.iskra.core.rooms.RoomKind.DIRECT) setPerson(Person.Builder().setName(room.name).build()) }
                .build()
            runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        }
        published = keys
    }

    companion object {
        /** Категория из `res/xml/shortcuts.xml` (`<share-target>`): ярлыки с ней показываются в меню «Поделиться». */
        const val CATEGORY = "wtf.iskra.directshare.CHAT"
        private const val MAX = 4
        private const val FACE_SIZE = 192
    }
}
