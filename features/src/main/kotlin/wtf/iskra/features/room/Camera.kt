package wtf.iskra.features.room

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import java.io.File
import java.util.UUID

/**
 * Снятое камерой и вставленное из буфера — до отправки. Камера — системное приложение
 * (`TakePicture` / `CaptureVideo`): ему отдаётся адрес в своей папке через `FileProvider` только на
 * запись этого одного файла, и разрешение на камеру приложению не нужно. Вставленное копируется сюда
 * же сразу: право читать чужой буфер — временное, а отправят, может быть, через минуту.
 *
 * Открытый текст на диске, как у открываемых файлов: папка пустеет после отправки и при запуске.
 */
internal object Camera {
    /** Пустой файл для снимка или ролика и адрес, по которому камера его запишет. */
    fun newCapture(context: Context, video: Boolean): Uri {
        val directory = directory(context).apply { mkdirs() }
        val file = File(directory, UUID.randomUUID().toString() + if (video) ".mp4" else ".jpg")
        return FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
    }

    /** Есть ли в буфере фото или видео — тогда в меню «+» есть «Вставить». */
    fun clipboardHasMedia(context: Context): Boolean {
        val description = clipboard(context)?.primaryClipDescription ?: return false
        return description.hasMimeType("image/*") || description.hasMimeType("video/*")
    }

    /** Фото или видео из буфера — копией у себя. Null — в буфере его нет или оно не читается. */
    suspend fun paste(context: Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val clip = clipboard(context)?.primaryClip ?: return@withContext null
            val item = (0 until clip.itemCount).map(clip::getItemAt).firstOrNull { it.uri != null } ?: return@withContext null
            val type = context.contentResolver.getType(item.uri) ?: return@withContext null
            val video = type.startsWith("video/")
            if (!video && !type.startsWith("image/")) return@withContext null
            val target = newCapture(context, video)
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                context.contentResolver.openOutputStream(target)?.use { input.copyTo(it) }
            } ?: return@withContext null
            target
        } catch (error: Exception) {
            Log.error(Log.Category.MEDIA, "вставка не прочиталась: ${Log.describe(error)}")
            null
        }
    }

    /** Убрать одно снятое или вставленное, когда оно ушло. Чужие адреса (галерея) не трогаются. */
    fun discard(context: Context, uri: Uri) {
        if (uri.authority != context.packageName + AUTHORITY_SUFFIX) return
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    /** Убрать всё снятое и вставленное — при запуске. */
    fun sweep(context: Context) {
        runCatching { directory(context).deleteRecursively() }
    }

    private fun clipboard(context: Context) = context.getSystemService(ClipboardManager::class.java)

    private fun directory(context: Context) = File(context.cacheDir, DIRECTORY)

    /** Совпадает с `file_paths.xml`. */
    private const val DIRECTORY = "captured"
    private const val AUTHORITY_SUFFIX = ".files"
}
