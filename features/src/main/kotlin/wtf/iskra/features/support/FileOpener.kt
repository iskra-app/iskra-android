package wtf.iskra.features.support

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
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
import wtf.iskra.core.media.VideoPreparation
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

    /** Убрать всё, что открывалось раньше. Перед каждым открытием. */
    fun sweep(context: Context) {
        runCatching { directory(context).deleteRecursively() }
    }

    /**
     * При запуске: и открытое прошлым запуском, и видео, которые играли, когда приложение упало.
     * Чужое приложение давно прочло первое, а плееров второго больше нет.
     */
    fun sweepOnLaunch(context: Context) {
        sweep(context)
        runCatching { File(context.cacheDir, PLAYING).deleteRecursively() }
        runCatching { File(context.cacheDir, RECORDING).deleteRecursively() }
        // Видео, которые уходили, когда процесс умер: SDK их уже прочёл или не прочтёт никогда.
        runCatching { VideoPreparation.outgoing(context.cacheDir).deleteRecursively() }
        wtf.iskra.features.room.Camera.sweep(context)
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

    /**
     * «Поделиться» из просмотрщика: тот же путь, что открытие, только `ACTION_SEND` и выбор, кому.
     * Копия ждёт в той же папке и уходит с тем же следующим открытием или запуском.
     */
    suspend fun share(context: Context, attachment: Attachment, loader: MediaLoader, title: String): Boolean = try {
        val file = decrypted(context, attachment, loader)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(attachment.mimeType ?: context.contentResolver.getType(uri) ?: ANY_TYPE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData — чтобы разрешение на чтение дошло и до выбранного приложения, а не только до выбора.
        send.clipData = ClipData.newRawUri(attachment.filename, uri)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.error(Log.Category.MEDIA, "поделиться не вышло: ${Log.describe(error)}")
        false
    }

    /**
     * «Сохранить в Фото»: в общую медиатеку, в `Pictures/Iskra` или `Movies/Iskra`. С Android 10 —
     * через `MediaStore` и без разрешений: приложение кладёт свой файл и не читает чужих (на iOS —
     * `addOnly`). Пока пишется — `IS_PENDING`: галерея не покажет половину файла.
     */
    suspend fun saveToGallery(context: Context, attachment: Attachment, loader: MediaLoader, isVideo: Boolean): Boolean = try {
        val file = decrypted(context, attachment, loader)
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, attachment.filename)
                attachment.mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + GALLERY_FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val item = resolver.insert(collection, values) ?: error("медиатека не приняла запись")
            try {
                resolver.openOutputStream(item)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("медиатека не открыла файл")
                resolver.update(item, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (error: Exception) {
                resolver.delete(item, null, null)
                throw error
            }
            // Копия в своей папке больше не нужна: сохранённое теперь — дело галереи.
            file.delete()
        }
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.error(Log.Category.MEDIA, "в галерею не сохранилось: ${Log.describe(error)}")
        false
    }

    /** Свежая расшифрованная копия в своей папке — прошлые убраны. */
    private suspend fun decrypted(context: Context, attachment: Attachment, loader: MediaLoader): File {
        withContext(Dispatchers.IO) { sweep(context) }
        return loader.file(attachment, directory(context))
    }

    private fun directory(context: Context) = File(context.cacheDir, DIRECTORY)

    /** Совпадает с `file_paths.xml` и `authorities` провайдера в манифесте. */
    private const val DIRECTORY = "opened"
    private const val AUTHORITY_SUFFIX = ".files"
    private const val ANY_TYPE = "*/*"

    /** Папка Искры в «Фото» и «Видео». */
    private const val GALLERY_FOLDER = "Iskra"

    /** Совпадает с `VideoFiles` — папка видео, которые сейчас играют. */
    private const val PLAYING = "playing"

    /** Совпадает с `VoiceRecorder` — запись голосового, пока она идёт. */
    private const val RECORDING = "recording"
}
