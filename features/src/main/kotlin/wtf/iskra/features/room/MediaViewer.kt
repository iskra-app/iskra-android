package wtf.iskra.features.room

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.LocalMediaLoader
import wtf.iskra.features.support.FileOpener
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max

/** Фото и видео чата, которые листает просмотрщик, — по времени, старое слева, как в ленте. */
internal fun List<Message>.viewable(): List<Message> = filter { it.kind is MessageKind.Image || it.kind is MessageKind.Video }

/**
 * Фото чата, открытое на весь экран, — галерея Telegram (`MediaPager` на iOS):
 *
 * - **страницы едут, хром стоит**: шапка (чьё, когда, «3 из 12») и подвал (подпись, лента миниатюр)
 *   не уезжают вместе с фото, между страницами — чёрное;
 * - **картинка — никогда не спиннер**: сразу кадр, который уже есть у ленты, во весь экран, и полный
 *   файл встаёт на его место, когда придёт;
 * - **одно нажатие** убирает всё лишнее, щипок или двойное нажатие увеличивают, смахивание вниз закрывает.
 *
 * Байты полного фото — только в памяти и только пока страница жива: в зашифрованной комнате это
 * чья-то личная фотография открытым текстом. Видео качается, только когда его страница впереди
 * (`rememberVideo`), и встаёт на паузу, когда его пролистнули; его кнопки — в подвале.
 * Сохранение и «поделиться» — когда будет файл, который живёт ровно столько же.
 */
@Composable
internal fun MediaViewer(items: List<Message>, startId: String, onClose: () -> Unit) {
    if (items.isEmpty()) return
    val start = items.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { items.size }
    val scope = rememberCoroutineScope()
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var dismissing by remember { mutableFloatStateOf(0f) }
    val players = remember { mutableStateMapOf<String, Player>() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().alpha(1f - dismissing).background(Color.Black))
            HorizontalPager(
                state = pager,
                pageSpacing = Theme.Size.galleryPageGap,
                userScrollEnabled = !zoomed && items.size > 1,
                key = { items[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val message = items[page]
                if (message.kind is MessageKind.Video) {
                    VideoPage(
                        message = message,
                        isCurrent = page == pager.currentPage,
                        onTap = { chrome = !chrome },
                        onDrag = { dismissing = it },
                        onDismiss = onClose,
                        onPlayer = { player -> if (player != null) players[message.id] = player else players.remove(message.id) },
                    )
                } else {
                    ZoomablePhoto(
                        message = message,
                        onTap = { chrome = !chrome },
                        onZoom = { if (page == pager.currentPage) zoomed = it },
                        onDrag = { dismissing = it },
                        onDismiss = onClose,
                    )
                }
            }
            val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
            AnimatedVisibility(chrome && dismissing == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                ViewerHeader(current, pager.currentPage + 1, items.size, onClose)
            }
            AnimatedVisibility(chrome && dismissing == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ViewerFooter(items, pager.currentPage, players[current.id]) { index -> scope.launch { pager.animateScrollToPage(index) } }
            }
        }
    }
}

/** Закрыть слева, чьё и когда посередине, сколько пройдено справа. */
@Composable
private fun ViewerHeader(message: Message, position: Int, total: Int, onClose: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(message.timestamp, locale) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(message.timestamp.toEpochMilli()))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = Theme.Backdrop.PLATE))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.message_media_close), tint = Color.White)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message.senderName, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = QUIET))
        }
        Box(Modifier.width(Theme.Size.minimumTapTarget + Theme.Spacing.close), contentAlignment = Alignment.CenterEnd) {
            if (total > 1) {
                Text(
                    stringResource(R.string.message_media_counter, position, total),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = QUIET),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Подпись фото, все фото чата полоской — текущее шире, нажатие ведёт туда, — и что с ним сделать. */
@Composable
private fun ViewerFooter(items: List<Message>, current: Int, player: Player?, onSelect: (Int) -> Unit) {
    val caption = items.getOrNull(current)?.kind?.picture?.caption
    val strip = rememberLazyListState(initialFirstVisibleItemIndex = current)
    LaunchedEffect(current) { strip.animateScrollToItem(max(0, current - STRIP_LEAD)) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = Theme.Backdrop.PLATE))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(vertical = Theme.Spacing.close),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        if (player != null) VideoControls(player)
        caption?.let { caption ->
            Text(
                caption,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = CAPTION_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Theme.Spacing.roomy),
            )
        }
        if (items.size > 1) {
            LazyRow(
                state = strip,
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.withinRun),
                contentPadding = PaddingValues(horizontal = Theme.Spacing.roomy),
                modifier = Modifier.height(Theme.Size.galleryThumbnail.height),
            ) {
                itemsIndexed(items, key = { _, message -> message.id }) { index, message ->
                    val attachment = message.kind.picture ?: return@itemsIndexed
                    val image = rememberThumbnail(attachment, Theme.Size.galleryThumbnailCurrent)
                    val isCurrent = index == current
                    Box(
                        Modifier
                            .size(
                                if (isCurrent) Theme.Size.galleryThumbnailCurrent else Theme.Size.galleryThumbnail.width,
                                Theme.Size.galleryThumbnail.height,
                            )
                            .clip(RoundedCornerShape(Theme.Spacing.tight))
                            .background(Color.White.copy(alpha = Theme.Backdrop.WASH))
                            .alpha(if (isCurrent) 1f else QUIET)
                            .clickable(
                                onClickLabel = stringResource(if (message.kind is MessageKind.Video) R.string.message_media_video else R.string.message_media_photo),
                            ) { onSelect(index) },
                    ) {
                        if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        items.getOrNull(current)?.let { ViewerActions(it) }
    }
}

/** Как прошло «Сохранить» — у текущей страницы; новая страница сохраняется заново. */
private enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/**
 * «Поделиться» и «Сохранить в Фото» — что делают со снимком впереди (`GalleryActions` на iOS). Файл
 * качается по нажатию: просмотрщик не держит полные файлы всех страниц ради кнопки, которую нажмут редко.
 */
@Composable
private fun ViewerActions(message: Message) {
    val attachment = message.kind.picture ?: return
    val context = LocalContext.current
    val loader = LocalMediaLoader.current ?: return
    val scope = rememberCoroutineScope()
    val shareTitle = stringResource(R.string.message_media_share)
    var save by remember(message.id) { mutableStateOf(SaveState.IDLE) }
    var sharing by remember(message.id) { mutableStateOf(false) }
    val isVideo = message.kind is MessageKind.Video || message.kind is MessageKind.VideoNote
    Row(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close), verticalAlignment = Alignment.CenterVertically) {
        IconButton(
            enabled = !sharing,
            onClick = {
                sharing = true
                scope.launch {
                    FileOpener.share(context, attachment, loader, shareTitle)
                    sharing = false
                }
            },
        ) {
            Icon(Icons.Filled.Share, contentDescription = shareTitle, tint = Color.White)
        }
        Spacer(Modifier.weight(1f))
        IconButton(
            enabled = save == SaveState.IDLE,
            onClick = {
                save = SaveState.SAVING
                scope.launch { save = if (FileOpener.saveToGallery(context, attachment, loader, isVideo)) SaveState.SAVED else SaveState.FAILED }
            },
        ) {
            if (save == SaveState.SAVING) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(Theme.Size.menuIcon))
            } else {
                Icon(
                    when (save) {
                        SaveState.SAVED -> Icons.Filled.CheckCircle
                        SaveState.FAILED -> Icons.Filled.ErrorOutline
                        else -> Icons.Filled.Download
                    },
                    contentDescription = stringResource(R.string.message_media_save),
                    tint = Color.White,
                )
            }
        }
    }
}

/**
 * Одна страница: кадр из ленты сразу, полный файл — поверх, когда придёт. Увеличенное фото двигается
 * пальцем, а не перелистывается; не увеличенное, смахнутое вниз или вверх, закрывает просмотрщик.
 */
@Composable
private fun ZoomablePhoto(
    message: Message,
    onTap: () -> Unit,
    onZoom: (Boolean) -> Unit,
    onDrag: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val attachment = (message.kind as MessageKind.Image).attachment
    val preview = rememberThumbnail(attachment, frame(message.kind, attachment).let { if (it.width > it.height) it.width else it.height })
    val full = rememberFullImage(message)
    val image = full ?: preview

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableFloatStateOf(0f) }
    var area by remember { mutableStateOf(IntSize.Zero) }

    // Граница — по самому фото, вписанному в экран, а не по экрану: иначе увеличенное уезжает
    // в чёрное поле над и под ним.
    fun clamp(value: Offset, at: Float): Offset {
        val ratio = image?.let { it.width.toFloat() / max(1, it.height) } ?: (area.width.toFloat() / max(1, area.height))
        val width = minOf(area.width.toFloat(), area.height * ratio)
        val height = width / ratio
        val x = max(0f, (width * at - area.width) / 2f)
        val y = max(0f, (height * at - area.height) / 2f)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }

    fun zoom(to: Float, around: Offset = Offset(area.width / 2f, area.height / 2f)) {
        val center = Offset(area.width / 2f, area.height / 2f)
        scale = to
        offset = if (to <= 1f) Offset.Zero else clamp((center - around) * (to - 1f), to)
        onZoom(to > 1f)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { at -> zoom(if (scale > 1f) 1f else DOUBLE_TAP_ZOOM, at) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var travelled = Offset.Zero
                    var vertical: Boolean? = null
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            val next = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            offset = clamp(offset + event.calculatePan(), next)
                            if (next != scale) {
                                scale = next
                                onZoom(next > 1f)
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (fingers == 1) {
                            // Не увеличенное: горизонталь — листать (её берёт пейджер), вертикаль — закрыть.
                            if (vertical == null) {
                                travelled += event.calculatePan()
                                if (travelled.getDistance() > viewConfiguration.touchSlop) vertical = abs(travelled.y) > abs(travelled.x)
                            }
                            if (vertical == true) {
                                drag += event.calculatePan().y
                                onDrag((abs(drag) / max(1, area.height)).coerceIn(0f, 1f))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (vertical == true) {
                        if (abs(drag) > area.height * DISMISS_FRACTION) {
                            onDismiss()
                        } else {
                            drag = 0f
                            onDrag(0f)
                        }
                    }
                    if (scale <= 1f) offset = Offset.Zero
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                image,
                contentDescription = attachment.caption ?: stringResource(R.string.message_media_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + drag
                },
            )
        }
    }
}

/** Полное фото, уменьшенное до двойного экрана: больше не разглядеть и при увеличении, а память одна. */
@Composable
private fun rememberFullImage(message: Message): ImageBitmap? {
    val loader = LocalMediaLoader.current
    val attachment = (message.kind as MessageKind.Image).attachment
    val side = with(LocalDensity.current) {
        val configuration = LocalConfiguration.current
        max(configuration.screenWidthDp, configuration.screenHeightDp) * density * FULL_SIZE_FACTOR
    }.toInt()
    var image by remember(attachment.mediaId) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(attachment.mediaId, loader) {
        if (loader == null) return@LaunchedEffect
        val bytes = runCatching { loader.content(attachment) }.getOrNull() ?: return@LaunchedEffect
        image = withContext(Dispatchers.Default) { decode(bytes, side) }
    }
    return image
}

private fun decode(bytes: ByteArray, side: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f

/** Смахнуть на пятую часть экрана — закрыть; меньше — фото возвращается. */
private const val DISMISS_FRACTION = 0.2f
private const val FULL_SIZE_FACTOR = 2f
private const val QUIET = 0.7f
private const val CAPTION_LINES = 4

/** Сколько миниатюр стоит слева от текущей, когда полоска едет за ней. */
private const val STRIP_LEAD = 3
