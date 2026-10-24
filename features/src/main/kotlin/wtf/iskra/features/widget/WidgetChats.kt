package wtf.iskra.features.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import wtf.iskra.core.rooms.RoomSummary
import java.io.File
import java.security.MessageDigest

/** Одна плитка виджета: чат, куда ведёт нажатие, и что на ней нарисовано. */
data class WidgetChat(val roomId: String, val accountId: String, val name: String, val unread: Boolean)

/**
 * Что показывает виджет «Избранные чаты» и как это доходит до него. Виджет рисуется системой, когда ей вздумается, — иногда без
 * живого сеанса, — поэтому читает не сеанс, а снимок, который сеанс оставил: список в настройках и лица файлами в `filesDir/faces/`
 * (имя — sha256 от комнаты и аккаунта). Снимок пишется только когда нарисованное изменилось ([fingerprint]): перерисовка
 * виджета стоит заряда.
 */
class WidgetChats(private val context: Context) {
    private val preferences get() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val faces get() = File(context.filesDir, "faces").also { it.mkdirs() }

    fun load(): List<WidgetChat> = preferences.getString(KEY, null).orEmpty().lines().mapNotNull { line ->
        val parts = line.split(SEPARATOR)
        if (parts.size != 4) null else WidgetChat(parts[0], parts[1], parts[2], parts[3] == "1")
    }

    fun fingerprint(): String? = preferences.getString(PRINT, null)

    /** Записать снимок; лица — уже нарисованные кружки. Старые, которых в снимке больше нет, убираются. */
    fun save(chats: List<WidgetChat>, images: Map<WidgetChat, Bitmap>, print: String) {
        val keep = HashSet<String>()
        for ((chat, bitmap) in images) {
            val name = faceName(chat)
            keep += name
            File(faces, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        faces.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        val text = chats.joinToString("\n") { listOf(it.roomId, it.accountId, it.name.clean(), if (it.unread) "1" else "0").joinToString(SEPARATOR) }
        preferences.edit().putString(KEY, text).putString(PRINT, print).apply()
    }

    fun face(chat: WidgetChat): Bitmap? = File(faces, faceName(chat)).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun clear() {
        preferences.edit().clear().apply()
        faces.deleteRecursively()
    }

    companion object {
        private const val FILE = "widget_chats"
        private const val KEY = "chats"
        private const val PRINT = "print"
        private const val SEPARATOR = "\t"

        /** Столько плиток помещает самый большой размер. */
        const val MAX = 8

        private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

        private fun faceName(chat: WidgetChat): String = MessageDigest.getInstance("SHA-256")
            .digest("${chat.accountId}|${chat.roomId}".toByteArray()).joinToString("") { "%02x".format(it) } + ".png"

        /**
         * Кого показать, когда человек ничего не выбирал: закреплённые («избранные» у Matrix), потом верх списка, без дублей. Всё, что
         * нарисует виджет, — из этих [limit] чатов.
         */
        fun pick(rooms: List<RoomSummary>, limit: Int = MAX): List<RoomSummary> =
            (rooms.filter { it.isPinned } + rooms).distinctBy { it.id }.take(limit)

        /** Что нарисовано, одной строкой: сравнивая её, обновляют виджет только если что-то в нём изменилось. */
        fun fingerprint(chats: List<WidgetChat>, avatars: List<String?>, locked: Boolean = false): String =
            (listOf(locked) + chats.zip(avatars).map { (chat, avatar) -> listOf(chat.roomId, chat.accountId, chat.name, chat.unread, avatar) }).joinToString("|")

        /** Лицо кружком: фото обрезается по кругу, существо уже круглое. */
        fun circle(source: Bitmap, size: Int): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            val side = minOf(source.width, source.height)
            val from = android.graphics.Rect((source.width - side) / 2, (source.height - side) / 2, (source.width + side) / 2, (source.height + side) / 2)
            canvas.drawBitmap(source, from, android.graphics.Rect(0, 0, size, size), paint)
            return out
        }
    }
}
