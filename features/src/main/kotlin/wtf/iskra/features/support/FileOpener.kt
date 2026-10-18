package wtf.iskra.features.support

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import wtf.iskra.core.media.MediaLoader
import wtf.iskra.core.support.Log
import wtf.iskra.core.timeline.Attachment
import java.io.File

/**
 * Открыть присланный файл — PDF, таблицу, архив. Порт `FileOpener` (ветка macOS): приложение не
 * пытается его нарисовать, файл отдаётся тому, что человек выбрал для таких файлов.
 *
 * Значит, открытый текст на диске, и у него должен быть хозяин. Другое приложение читает файл *после*
 * того, как мы вернулись, и когда оно закончит — нам не скажут. Поэтому копия лежит в своей папке
 * (`cache/opened`, отдаётся через `FileProvider` только на чтение), и папка пустеет на входе:
 * открытие чего угодно убирает то, что оставило прошлое, и запуск приложения — тоже. Окно, в котором
 * расшифрованный файл лежит на диске, — «до следующего открытия или запуска», а не «пока система не
 * подметёт кэш».
 */
object FileOpener {
    private val mutableOpening = MutableStateFlow<String?>(null)

    /** Какое вложение сейчас качается, чтобы его кружок показал это. */
    val opening: StateFlow<String?> = mutableOpening.asStateFlow()

    /** Убрать всё, что открывалось раньше. При запуске и перед каждым открытием. */
    fun sweep(context: Context) {
        runCatching { directory(context).deleteRecursively() }
    }

    /** False — не вышло: не скачалось или открыть нечем. */
    suspend fun open(context: Context, attachment: Attachment, loader: MediaLoader): Boolean {
        if (mutableOpening.value != null) return false
        mutableOpening.value = attachment.mediaId
        return try {
            withContext(Dispatchers.IO) { sweep(context) }
            val file = loader.file(attachment, directory(context))
            val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, attachment.mimeType ?: context.contentResolver.getType(uri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Log.info(Log.Category.MEDIA, "файл открыть нечем")
            false
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "файл не открылся: ${Log.describe(error)}")
            false
        } finally {
            mutableOpening.value = null
        }
    }

    private fun directory(context: Context) = File(context.cacheDir, DIRECTORY)

    /** Совпадает с `file_paths.xml` и `authorities` провайдера в манифесте. */
    private const val DIRECTORY = "opened"
    private const val AUTHORITY_SUFFIX = ".files"
}
