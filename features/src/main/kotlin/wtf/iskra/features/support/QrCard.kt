package wtf.iskra.features.support

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import androidx.core.graphics.PathParser
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.rooms.MatrixLink
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.SparkShape
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import java.io.File
import kotlin.math.roundToInt

/**
 * Код, на который наводят камеру: чей он, сам код и адрес под ним — свой из настроек, комнаты из «О чате». Порт `QRCodeSheet`
 * без вращающейся голографической карточки (она отдельным пунктом).
 *
 * Карточка — *одна картинка*, и на экране, и в «Поделиться»: белая при любой теме, потому что QR на тёмном камера может не
 * прочесть, и поэтому она не берёт цвета схемы. В коде — ссылка `s.iskra.wtf`, не голый ID (`MatrixLink`, там же — почему);
 * ID написан под кодом для того, кто стоит рядом и предпочтёт набрать.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrCardSheet(name: String, subtitle: String, link: MatrixLink, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Синхронно: кодирование — миллисекунды, а лист, чей контент вырос после появления, не пересчитывает, где ему остановиться.
    val card = remember(name, subtitle, link) { QrCard.render(name, subtitle, link.url) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Theme.Spacing.generous).padding(bottom = Theme.Spacing.generous),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
        ) {
            Text(stringResource(R.string.qr_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Image(
                card.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier.widthIn(max = Theme.Size.qrCard).fillMaxWidth(),
            )
            PrimaryActionButton(onClick = { scope.launch { QrCard.share(context, card, name) } }) {
                Icon(Icons.Filled.Share, contentDescription = null)
                Text(stringResource(R.string.profile_share), modifier = Modifier.padding(start = Theme.Spacing.close))
            }
        }
    }
}

internal object QrCard {
    private const val WIDTH = 720
    private const val MARGIN = 48
    private const val CODE = WIDTH - 2 * MARGIN
    private const val CORNER = 56f
    private const val NAME_SIZE = 52f
    private const val SUBTITLE_SIZE = 36f
    private const val GAP = 32
    private const val DIRECTORY = "shared"
    private const val CENTRE_SHARE = 0.28

    /**
     * Модули кода с чистым квадратом посередине под искру. Коррекция `H`, и искра — тому причина: середина закрыта нарочно, а
     * `H` восстанавливает до 30 % кода по остальному (`QRCode` нативной Искры, `qr.ts` веба).
     *
     * Квадрат — нечётного числа модулей: код всегда нечётной ширины, и нечётный квадрат по центру начинается и кончается на
     * границе модуля, а не посередине. Полмодуля, торчащие у края подложки, — то, из-за чего логотип на QR выглядит наклеенным.
     */
    class Modules(val size: Int, private val dark: BooleanArray) {
        fun isDark(x: Int, y: Int) = dark[y * size + x]

        /** Сколько модулей в чистом квадрате: чуть больше четверти, нечётное. */
        val centre: Int = (size * CENTRE_SHARE).roundToInt().let { if (it % 2 == 0) it + 1 else it }

        /** Искра — доля стороны: чистый квадрат минус модуль белого с каждой стороны, чтобы она не касалась модулей. */
        val sparkFraction: Float get() = (centre - 2).toFloat() / size
    }

    fun modules(text: String): Modules {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.CHARACTER_SET to "UTF-8")
        // Ширина 0 — самая малая: один пиксель на модуль, без полей.
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = matrix.width
        val centre = Modules(size, BooleanArray(size * size)).centre
        val from = (size - centre) / 2
        val dark = BooleanArray(size * size) { index ->
            val x = index % size
            val y = index / size
            matrix.get(x, y) && !(x in from until from + centre && y in from until from + centre)
        }
        return Modules(size, dark)
    }

    /** Код чёрным по белому, целыми пикселями на модуль, и чёрная искра в чистой середине — как на нативной и в вебе. */
    fun code(text: String, side: Int): Bitmap {
        val modules = modules(text)
        val unit = side / modules.size
        val drawn = unit * modules.size
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK }
        val offset = (side - drawn) / 2f
        for (y in 0 until modules.size) {
            for (x in 0 until modules.size) {
                if (modules.isDark(x, y)) {
                    canvas.drawRect(offset + x * unit, offset + y * unit, offset + (x + 1) * unit, offset + (y + 1) * unit, paint)
                }
            }
        }
        val spark = PathParser.createPathFromPathData(SparkShape.PATH)
        if (spark != null) {
            spark.fillType = Path.FillType.EVEN_ODD
            val box = drawn * modules.sparkFraction
            val scale = minOf(box / SparkShape.WIDTH, box / SparkShape.HEIGHT)
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(side / 2f - SparkShape.WIDTH * scale / 2f, side / 2f - SparkShape.HEIGHT * scale / 2f)
            }
            spark.transform(matrix)
            canvas.drawPath(spark, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
        }
        return bitmap
    }

    /** Лицо карточки: имя, код, адрес. Белая при любой теме и прозрачная за скруглёнными углами. */
    fun render(name: String, subtitle: String, link: String): Bitmap {
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = NAME_SIZE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = SUBTITLE_SIZE
        }
        val nameLayout = layout(name, namePaint)
        val subtitleLayout = layout(subtitle, subtitlePaint)
        val height = MARGIN + nameLayout.height + GAP + CODE + GAP + subtitleLayout.height + MARGIN
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), height.toFloat()), CORNER, CORNER, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        var top = MARGIN.toFloat()
        canvas.withTranslation(MARGIN.toFloat(), top) { nameLayout.draw(this) }
        top += nameLayout.height + GAP
        canvas.drawBitmap(code(link, CODE), MARGIN.toFloat(), top, null)
        top += CODE + GAP
        canvas.withTranslation(MARGIN.toFloat(), top) { subtitleLayout.draw(this) }
        return bitmap
    }

    private fun layout(text: String, paint: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, CODE)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

    private inline fun Canvas.withTranslation(x: Float, y: Float, block: Canvas.() -> Unit) {
        save()
        translate(x, y)
        block()
        restore()
    }

    /** Картинка — то, что человек хочет отправить, показывая код: на другой экран или на печать. Файл лежит в кэше, пока его не подметёт система. */
    suspend fun share(context: Context, card: Bitmap, name: String) {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, DIRECTORY).apply { deleteRecursively(); mkdirs() }
            File(directory, "iskra-qr.png").also { target -> target.outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
