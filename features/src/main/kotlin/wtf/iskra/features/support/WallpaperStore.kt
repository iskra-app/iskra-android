package wtf.iskra.features.support

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import wtf.iskra.design.Wallpaper
import java.io.File

/**
 * Единственная картинка, которую человек положил за свои разговоры, и где она лежит. Порт `WallpaperStore`.
 *
 * На этом устройстве и больше нигде. Обои — не данные аккаунта: не синхронизируются, не попадают в зашифрованное хранилище и не подходят к домашнему
 * серверу, поэтому лежат обычным файлом в собственном каталоге приложения.
 *
 * Один на процесс, что не привычка проекта и здесь правильная форма: обои рисует каждая комната, и хранилище на комнату читало бы один и тот же JPEG с диска
 * на каждый открытый разговор. Картинка — один факт о приложении, она держится один раз, и комнаты за ней наблюдают.
 */
internal object WallpaperStore {
    private val mutablePhoto = MutableStateFlow<ImageBitmap?>(null)

    /** Картинка, когда прочитана. Null до этого и навсегда, если файла нет или он не читается, — тогда разговор просто стоит на странице приложения. */
    val photo: StateFlow<ImageBitmap?> = mutablePhoto.asStateFlow()

    private val mutableLuminance = MutableStateFlow<Float?>(null)

    /** Средняя яркость картинки, 0..1: по ней слова на обоях светлеют или темнеют. Меряется один раз, когда картинку прочли. */
    val luminance: StateFlow<Float?> = mutableLuminance.asStateFlow()

    @Volatile
    private var hasLoaded = false

    private fun file(context: Context) = File(context.filesDir, "wallpaper.jpg")

    /**
     * Читает картинку с диска один раз. Не на главном потоке: обои могут быть двенадцатью мегапикселями, а раскодировать их там, где рисуется разговор, —
     * видимая заминка на первой комнате после запуска.
     */
    suspend fun load(context: Context) {
        if (hasLoaded) return
        hasLoaded = true
        val file = file(context)
        val decoded = withContext(Dispatchers.IO) { runCatching { if (file.exists()) decode(file.readBytes()) else null }.getOrNull() } ?: return
        mutablePhoto.value = decoded.asImageBitmap()
        mutableLuminance.value = measure(decoded)
    }

    /**
     * Оставляет выбранную фотографию обоями. Байты пишутся как пришли из библиотеки, не пережимаются: JPEG, пересохранённый ради хранения, — это JPEG,
     * сделанный чуть хуже без причины, а этот файл нужен, чтобы на него смотреть.
     */
    suspend fun store(context: Context, data: ByteArray): Boolean {
        val bitmap = withContext(Dispatchers.IO) { decode(data) }
        if (bitmap == null) {
            Log.error(Log.Category.MEDIA, "выбранные обои не раскодировались")
            return false
        }
        withContext(Dispatchers.IO) {
            // Не записалось — на экране всё равно остаётся: картинка, что человек только выбрал, уже его обои, а не пережила запись только перезапуск, и говорить
            // об этом посреди выбора фона значило бы делать драму из JPEG.
            runCatching { file(context).writeBytes(data) }.onFailure { Log.error(Log.Category.MEDIA, "обои не записались: ${Log.describe(it)}") }
        }
        hasLoaded = true
        mutablePhoto.value = bitmap.asImageBitmap()
        mutableLuminance.value = measure(bitmap)
        return true
    }

    /** Забывает её: выход или выбор другого фона после своего. */
    fun forget(context: Context) {
        mutablePhoto.value = null
        mutableLuminance.value = null
        hasLoaded = true
        runCatching { file(context).delete() }
    }

    /** Раскодирует с уменьшением до размера экрана: двенадцать мегапикселей на обоях — это память без пользы. */
    private fun decode(data: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Средняя относительная яркость по миниатюре в несколько пикселей — это то, что глаз усредняет с вытянутой руки, и это ничего не стоит. */
    private fun measure(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, THUMBNAIL, THUMBNAIL, true)
        val pixels = IntArray(THUMBNAIL * THUMBNAIL)
        small.getPixels(pixels, 0, THUMBNAIL, 0, 0, THUMBNAIL, THUMBNAIL)
        val total = pixels.sumOf { pixel ->
            Wallpaper.luminance(
                android.graphics.Color.red(pixel) / CHANNEL,
                android.graphics.Color.green(pixel) / CHANNEL,
                android.graphics.Color.blue(pixel) / CHANNEL,
            ).toDouble()
        }
        return (total / pixels.size).toFloat()
    }

    private const val MAX_SIDE = 2048
    private const val THUMBNAIL = 16
    private const val CHANNEL = 255f
}
