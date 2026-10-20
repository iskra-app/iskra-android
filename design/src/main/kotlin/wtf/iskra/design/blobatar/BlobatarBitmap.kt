//  The bitmap side of the blobatar port. The generator it draws is ported from blobatar
//  (https://blobatar.dev), MIT licence, Copyright (c) 2026 Alain. See Licenses/blobatar.txt.
//  Kotlin port of BlobatarImage in IskraDesign.

package wtf.iskra.design.blobatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb

/**
 * То же существо, что [BlobatarFace], но картинкой — для мест, где Compose нет: лицо отправителя на
 * уведомлении (`Person` в `MessagingStyle`) и на ярлыке разговора. Без фото человек на экране
 * блокировки выглядит так же, как в списке чатов, а не буквой, которую нарисовала система.
 *
 * С подложкой-кругом: систему просят обрезать картинку в круг, и существо без подложки на тёмной шторке
 * пропадало бы краями.
 */
object BlobatarBitmap {
    fun render(seed: String, size: Int): Bitmap {
        val drawing = BlobatarCache.drawing(seed, Blobatar.Backdrop.CIRCLE)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val grown = size * drawing.magnification.toFloat()
        canvas.translate((size - grown) / 2, (size - grown) / 2)
        // Пути — заново, не из кэша рисунка: тот кэширует под один масштаб и живёт на главном потоке, а
        // уведомление рисуется в фоне.
        val scale = grown / FIGURE_SIDE
        for (mark in drawing.figure.marks) {
            paint.color = mark.color.toArgb()
            canvas.drawPath(mark.path(scale).asAndroidPath(), paint)
        }
        return bitmap
    }

    /** Существо рисуется в квадрате 100×100 (см. [BlobatarFace]). */
    private const val FIGURE_SIDE = 100f
}
