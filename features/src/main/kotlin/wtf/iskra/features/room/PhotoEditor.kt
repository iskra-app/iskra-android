package wtf.iskra.features.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/** Шесть красок редактора (`PhotoInk`): те же слова, что на iOS. */
private enum class Ink(val color: Color, val title: Int) {
    WHITE(Color.White, R.string.photo_editor_white),
    BLACK(Color.Black, R.string.photo_editor_black),
    RED(Color(0xFFFF3B30), R.string.photo_editor_red),
    YELLOW(Color(0xFFFFCC00), R.string.photo_editor_yellow),
    GREEN(Color(0xFF34C759), R.string.photo_editor_green),
    BLUE(Color(0xFF007AFF), R.string.photo_editor_blue),
}

private enum class Tool { DRAW, TEXT, CROP }

/**
 * Правка фото перед отправкой (`PhotoEditor`): рисунок, подпись, обрезка и поворот. Исходник не трогается — итог пишется новым файлом
 * (JPEG без метаданных: `Bitmap.compress` EXIF не пишет), и вложение подменяется им. [onDone] получает адрес этого файла.
 */
@Composable
internal fun PhotoEditorDialog(source: Uri, onDone: (Uri) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var base by remember(source) { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        base = withContext(Dispatchers.IO) { decode(context, source) }
        if (base == null) failed = true
    }
    // Повороты кладут в историю вместе с правками: «Отменить» откатывает и их.
    var turns by remember(source) { mutableIntStateOf(0) }
    var edits by remember(base) { mutableStateOf(base?.let { PhotoEdits(it.width, it.height) }) }
    val history = remember(source) { ArrayDeque<Pair<PhotoEdits, Int>>() }
    var tool by remember { mutableStateOf(Tool.DRAW) }
    var ink by remember { mutableStateOf(Ink.RED) }
    var typing by remember { mutableStateOf<PhotoEdits.Point?>(null) }
    var nextLabel by remember { mutableIntStateOf(1) }
    var saving by remember { mutableStateOf(false) }

    val shown = remember(base, turns) { base?.let { rotated(it, turns) }?.asImageBitmap() }
    fun change(make: (PhotoEdits) -> PhotoEdits) {
        val current = edits ?: return
        history.addLast(current to turns)
        edits = make(current)
    }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.photo_editor_cancel), color = Color.White) }
                Box(Modifier.weight(1f))
                androidx.compose.material3.IconButton(
                    onClick = {
                        history.removeLastOrNull()?.let { (previous, previousTurns) -> edits = previous; turns = previousTurns }
                    },
                    enabled = history.isNotEmpty(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.photo_editor_undo), tint = if (history.isNotEmpty()) Color.White else Color.Gray)
                }
                TextButton(
                    enabled = shown != null && !saving,
                    onClick = {
                        val finished = edits ?: return@TextButton
                        val bitmap = base ?: return@TextButton
                        saving = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { render(context, rotated(bitmap, turns), finished) }
                            saving = false
                            if (saved != null) onDone(saved) else failed = true
                        }
                    },
                ) { Text(stringResource(R.string.photo_editor_done), color = Color.White) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val image = shown
                val current = edits
                if (image != null && current != null) {
                    Stage(
                        image, current, tool, ink,
                        onStroke = { stroke -> change { it.copy(strokes = it.strokes + stroke) } },
                        onTap = { typing = it },
                        onMoveLabel = { id, to -> edits = current.copy(labels = current.labels.map { if (it.id == id) it.copy(at = to) else it }) },
                        onGestureStart = { history.addLast(current to turns) },
                        onCrop = { frame -> edits = current.copy(crop = frame) },
                    )
                }
            }
            if (tool != Tool.CROP) {
                Row(Modifier.fillMaxWidth().padding(Theme.Spacing.close), horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal, Alignment.CenterHorizontally)) {
                    Ink.entries.forEach { option ->
                        Box(
                            Modifier
                                .size(Theme.Size.swatch)
                                .background(option.color, CircleShape)
                                .border(if (option == ink) Theme.Border.thick else Theme.Border.hairline, if (option == ink) Color.White else Color.Gray, CircleShape)
                                .clickable(role = Role.RadioButton, onClickLabel = stringResource(option.title)) { ink = option },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = Theme.Spacing.close), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolButton(Icons.Filled.Edit, R.string.photo_editor_draw, tool == Tool.DRAW) { tool = Tool.DRAW }
                ToolButton(Icons.Filled.TextFields, R.string.photo_editor_text, tool == Tool.TEXT) { tool = Tool.TEXT }
                ToolButton(Icons.Filled.Crop, R.string.photo_editor_crop, tool == Tool.CROP) {
                    tool = Tool.CROP
                    if (edits?.crop == null) edits = edits?.let { it.copy(crop = it.frame) }
                }
                ToolButton(Icons.Filled.RotateLeft, R.string.photo_editor_rotate, false) {
                    change { it.turned() }
                    turns += 1
                }
            }
        }
    }
    typing?.let { at ->
        var text by remember(at) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { typing = null },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        val id = nextLabel++
                        val size = (edits?.let { maxOf(it.width, it.height) } ?: 1000) / LABEL_DIVISOR
                        change { it.copy(labels = it.labels + PhotoEdits.Label(id, text.trim(), ink.color.toArgb(), at, size.toFloat())) }
                        typing = null
                    },
                ) { Text(stringResource(R.string.photo_editor_done)) }
            },
            dismissButton = { TextButton(onClick = { typing = null }) { Text(stringResource(R.string.photo_editor_cancel)) } },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(stringResource(R.string.photo_editor_text_prompt)) }) },
        )
    }
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false; if (base == null) onCancel() },
            confirmButton = { TextButton(onClick = { failed = false; if (base == null) onCancel() }) { Text(stringResource(R.string.photo_editor_ok)) } },
            text = { Text(stringResource(R.string.photo_editor_failed)) },
        )
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(title)
    Column(
        Modifier.clickable(role = Role.Button, onClick = onClick).padding(Theme.Spacing.close),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Color.White else Color.Gray)
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) Color.White else Color.Gray)
    }
}

/** Картинка, поверх неё рисунок, подписи и (при обрезке) рамка; жесты — по инструменту. */
@Composable
private fun Stage(
    image: ImageBitmap,
    edits: PhotoEdits,
    tool: Tool,
    ink: Ink,
    onStroke: (PhotoEdits.Stroke) -> Unit,
    onTap: (PhotoEdits.Point) -> Unit,
    onMoveLabel: (Int, PhotoEdits.Point) -> Unit,
    onGestureStart: () -> Unit,
    onCrop: (PhotoEdits.Frame) -> Unit,
) {
    val density = LocalDensity.current
    var live by remember { mutableStateOf<List<PhotoEdits.Point>>(emptyList()) }
    var dragging by remember { mutableStateOf<Int?>(null) }
    var handle by remember { mutableStateOf<CropHandle?>(null) }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(tool, edits.width, edits.height, ink) {
                val scale = minOf(size.width.toFloat() / edits.width, size.height.toFloat() / edits.height)
                val origin = Offset((size.width - edits.width * scale) / 2, (size.height - edits.height * scale) / 2)
                fun toImage(p: Offset) = PhotoEdits.Point((p.x - origin.x) / scale, (p.y - origin.y) / scale)
                val reach = with(density) { REACH.dp.toPx() } / scale
                when (tool) {
                    Tool.DRAW -> detectDragGestures(
                        onDragStart = { live = listOf(toImage(it)) },
                        onDrag = { change, _ -> live = live + toImage(change.position) },
                        onDragEnd = {
                            if (live.size > 1) onStroke(PhotoEdits.Stroke(ink.color.toArgb(), maxOf(edits.width, edits.height) / STROKE_DIVISOR, live))
                            live = emptyList()
                        },
                        onDragCancel = { live = emptyList() },
                    )
                    Tool.TEXT -> {
                        detectTapGestures(onTap = { onTap(toImage(it)) })
                    }
                    Tool.CROP -> detectDragGestures(
                        onDragStart = {
                            val p = toImage(it)
                            handle = CropHandle.at(p.x, p.y, edits.frame, reach)
                        },
                        onDrag = { change, drag ->
                            val grabbed = handle ?: return@detectDragGestures
                            change.consume()
                            val bounds = PhotoEdits.Frame(0f, 0f, edits.width.toFloat(), edits.height.toFloat())
                            onCrop(grabbed.move(edits.frame, drag.x / scale, drag.y / scale, bounds))
                        },
                        onDragEnd = { handle = null },
                    )
                }
            }
            .then(
                if (tool == Tool.TEXT) {
                    Modifier.pointerInput(edits.labels, edits.width) {
                        val scale = minOf(size.width.toFloat() / edits.width, size.height.toFloat() / edits.height)
                        val origin = Offset((size.width - edits.width * scale) / 2, (size.height - edits.height * scale) / 2)
                        detectDragGestures(
                            onDragStart = { start ->
                                val p = PhotoEdits.Point((start.x - origin.x) / scale, (start.y - origin.y) / scale)
                                dragging = edits.labels.minByOrNull { kotlin.math.hypot(it.at.x - p.x, it.at.y - p.y) }
                                    ?.takeIf { kotlin.math.hypot(it.at.x - p.x, it.at.y - p.y) < with(density) { REACH.dp.toPx() } / scale * 2 }?.id
                                if (dragging != null) onGestureStart()
                            },
                            onDrag = { change, drag ->
                                val id = dragging ?: return@detectDragGestures
                                val label = edits.labels.firstOrNull { it.id == id } ?: return@detectDragGestures
                                change.consume()
                                onMoveLabel(id, PhotoEdits.Point(label.at.x + drag.x / scale, label.at.y + drag.y / scale))
                            },
                            onDragEnd = { dragging = null },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val scale = minOf(size.width / edits.width, size.height / edits.height)
        val origin = Offset((size.width - edits.width * scale) / 2, (size.height - edits.height * scale) / 2)
        fun at(p: PhotoEdits.Point) = Offset(origin.x + p.x * scale, origin.y + p.y * scale)
        drawImage(image, dstOffset = androidx.compose.ui.unit.IntOffset(origin.x.toInt(), origin.y.toInt()), dstSize = androidx.compose.ui.unit.IntSize((edits.width * scale).toInt(), (edits.height * scale).toInt()))
        fun path(points: List<PhotoEdits.Point>) = Path().apply {
            points.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
        }
        edits.strokes.forEach { s ->
            drawPath(path(s.points), Color(s.color), style = Stroke(s.width * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (live.size > 1) drawPath(path(live), ink.color, style = Stroke(maxOf(edits.width, edits.height) / STROKE_DIVISOR * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawIntoCanvas { canvas ->
            edits.labels.forEach { label -> drawLabel(canvas.nativeCanvas, label, scale, origin.x, origin.y) }
        }
        if (tool == Tool.CROP) {
            val f = edits.frame
            val topLeft = at(PhotoEdits.Point(f.left, f.top))
            val frameSize = Size(f.width * scale, f.height * scale)
            // Всё вне рамки — тёмной вуалью: что останется, видно без догадок.
            drawRect(Color.Black.copy(alpha = VEIL), Offset.Zero, Size(size.width, topLeft.y))
            drawRect(Color.Black.copy(alpha = VEIL), Offset(0f, topLeft.y + frameSize.height), Size(size.width, size.height - topLeft.y - frameSize.height))
            drawRect(Color.Black.copy(alpha = VEIL), Offset(0f, topLeft.y), Size(topLeft.x, frameSize.height))
            drawRect(Color.Black.copy(alpha = VEIL), Offset(topLeft.x + frameSize.width, topLeft.y), Size(size.width - topLeft.x - frameSize.width, frameSize.height))
            drawRect(Color.White, topLeft, frameSize, style = Stroke(Theme.Border.thick.toPx()))
            listOf(topLeft, topLeft + Offset(frameSize.width, 0f), topLeft + Offset(0f, frameSize.height), topLeft + Offset(frameSize.width, frameSize.height))
                .forEach { drawCircle(Color.White, Theme.Spacing.close.toPx(), it) }
        }
    }
}

private fun drawLabel(canvas: AndroidCanvas, label: PhotoEdits.Label, scale: Float, ox: Float, oy: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = label.color
        textSize = label.size * scale
        isFakeBoldText = true
        setShadowLayer(label.size * scale / SHADOW_DIVISOR, 0f, 0f, android.graphics.Color.argb(SHADOW_ALPHA, 0, 0, 0))
    }
    canvas.drawText(label.text, ox + label.at.x * scale, oy + label.at.y * scale, paint)
}

private fun rotated(source: Bitmap, turns: Int): Bitmap {
    val quarter = ((turns % 4) + 4) % 4
    if (quarter == 0) return source
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(-90f * quarter) }, true)
}

/** Фото в память, не больше [MAX_SIDE] по длинной стороне: правка картинки в двадцать мегапикселей не нужна, и телефону она не по карману. */
private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return@runCatching null
    // Фото «лежит на боку» с меткой поворота в EXIF — ставится как его показывает галерея.
    val orientation = context.contentResolver.openInputStream(uri)?.use {
        androidx.exifinterface.media.ExifInterface(it).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
    } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return@runCatching decoded
    }
    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}.getOrNull()

/** Итог: картинка, на ней рисунок и подписи, по рамке обрезки — JPEG в нашем каталоге. Null — не вышло, и фото остаётся как было. */
private fun render(context: Context, image: Bitmap, edits: PhotoEdits): Uri? = runCatching {
    val canvasBitmap = image.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = AndroidCanvas(canvasBitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    edits.strokes.forEach { s ->
        paint.color = s.color
        paint.strokeWidth = s.width
        val path = android.graphics.Path()
        s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        canvas.drawPath(path, paint)
    }
    edits.labels.forEach { drawLabel(canvas, it, 1f, 0f, 0f) }
    val frame = edits.frame
    val cropped = Bitmap.createBitmap(
        canvasBitmap,
        frame.left.toInt().coerceIn(0, canvasBitmap.width - 1),
        frame.top.toInt().coerceIn(0, canvasBitmap.height - 1),
        frame.width.toInt().coerceIn(1, canvasBitmap.width - frame.left.toInt().coerceAtLeast(0)),
        frame.height.toInt().coerceIn(1, canvasBitmap.height - frame.top.toInt().coerceAtLeast(0)),
    )
    val target = Camera.newCapture(context, video = false)
    context.contentResolver.openOutputStream(target)?.use { cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) } ?: return@runCatching null
    target
}.getOrElse {
    Log.error(Log.Category.MEDIA, "правка фото не сохранилась: ${Log.describe(it)}")
    null
}

private const val MAX_SIDE = 2048
private const val JPEG_QUALITY = 92
private const val LABEL_DIVISOR = 14
private const val STROKE_DIVISOR = 90f
private const val REACH = 28
private const val VEIL = 0.55f
private const val SHADOW_DIVISOR = 8f
private const val SHADOW_ALPHA = 140
