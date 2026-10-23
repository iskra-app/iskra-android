package wtf.iskra.features.support

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import kotlin.math.roundToInt

/**
 * Выбрать картинку и вырезать из неё лицо: системный выбор фото, затем [AvatarCropper]. Возвращает,
 * чем открыть выбор. [onCropped] получает готовый JPEG; [onUnreadable] — картинка, которую галерея не
 * отдала или которую не прочесть: до сервера она не дошла, но сказать об этом всё равно надо.
 */
@Composable
fun rememberAvatarPicker(
    shape: AvatarShape = AvatarShape.CIRCLE,
    onCropped: (ByteArray) -> Unit,
    onUnreadable: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    val cropped by rememberUpdatedState(onCropped)
    val unreadable by rememberUpdatedState(onUnreadable)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val image = withContext(Dispatchers.IO) { AvatarCrop.decode(context, uri) }
            if (image == null) unreadable() else cropping = image
        }
    }

    cropping?.let { image ->
        AvatarCropper(
            image = image,
            shape = shape,
            onCancel = { cropping = null },
            onDone = { jpeg ->
                cropping = null
                cropped(jpeg)
            },
            onFailed = {
                cropping = null
                unreadable()
            },
        )
    }

    return remember(picker) { { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
}

/**
 * Вырезать новое лицо из картинки — порт `AvatarCropper.swift`. Квадрат, а в нём — контур самого лица:
 * круг или скруглённый квадрат пространства; всё снаружи контура остаётся на месте, только под тёмной
 * вуалью, — видно, что не войдёт и куда сдвинуть, чтобы вошло.
 *
 * Двигать и приближать, ничего больше: ни поворота, ни рисования. Уходит сам квадрат — контур
 * показывает, как лицо будет видно, а не режет (см. [AvatarCrop]).
 *
 * Тёмный экран при любой теме, как просмотр медиа: картинку смотрят на чёрном, а не на цвете схемы.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AvatarCropper(
    image: Bitmap,
    shape: AvatarShape,
    onCancel: () -> Unit,
    onDone: (ByteArray) -> Unit,
    onFailed: () -> Unit,
) {
    val picture = remember(image) { image.asImageBitmap() }
    var zoom by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    var side by remember(image) { mutableFloatStateOf(1f) }
    var rendering by remember(image) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    fun crop() = AvatarCrop(image.width.toFloat(), image.height.toFloat(), side, zoom, offset.x, offset.y)

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.avatar_crop_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.avatar_crop_cancel))
                    }
                },
                actions = {
                    TextButton(
                        enabled = !rendering,
                        onClick = {
                            rendering = true
                            val settled = crop().run { clamped().let { (x, y) -> copy(offsetX = x, offsetY = y) } }
                            scope.launch {
                                val jpeg = withContext(Dispatchers.Default) { settled.render(image) }
                                if (jpeg != null) onDone(jpeg) else onFailed()
                            }
                        },
                    ) { Text(stringResource(R.string.avatar_crop_done), color = Color.White) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                windowInsets = WindowInsets(0),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val square = with(density) { minOf(maxWidth, maxHeight).toPx() - Theme.Spacing.generous.toPx() * 2 }.coerceAtLeast(1f)
                // «Готово» — над этим полем и знает квадрат только отсюда.
                SideEffect { side = square }
                val squareDp = with(density) { square.toDp() }
                val held by rememberUpdatedState(square)
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(image) {
                            // Сдвиг и щипок разом: палец ведёт картинку, щипок приближает, и всё время
                            // картинка держится так, чтобы закрывать квадрат.
                            detectTransformGestures { _, pan, change, _ ->
                                zoom = AvatarCrop.clampedZoom(zoom * change)
                                val (x, y) = AvatarCrop(image.width.toFloat(), image.height.toFloat(), held, zoom)
                                    .clamped(offset.x + pan.x, offset.y + pan.y)
                                offset = Offset(x, y)
                            }
                        },
                ) {
                    val current = AvatarCrop(image.width.toFloat(), image.height.toFloat(), square, zoom, offset.x, offset.y)
                    val (x, y) = current.clamped()
                    val width = current.displayedWidth
                    val height = current.displayedHeight
                    drawImage(
                        picture,
                        dstOffset = IntOffset(
                            (center.x + x - width / 2).roundToInt(),
                            (center.y + y - height / 2).roundToInt(),
                        ),
                        dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                        filterQuality = FilterQuality.High,
                    )

                    val hole = Offset(center.x - square / 2, center.y - square / 2)
                    val outline = Path().apply {
                        addOutline(shape.shape(squareDp).createOutline(Size(square, square), layoutDirection, this@Canvas))
                        translate(hole)
                    }
                    // Всё поле без контура лица посередине, залитое чёт-нечет, — вуаль над тем, что лицо не покажет.
                    val veil = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addPath(outline)
                    }
                    drawPath(veil, Color.Black.copy(alpha = Theme.Backdrop.CROP_VEIL))
                    drawPath(outline, Color.White.copy(alpha = Theme.Backdrop.CROP_OUTLINE), style = Stroke(Theme.Border.hairline.toPx()))
                }
            }
        }
    }
}

/**
 * Картинка, которую можно сменить, — порт `EditableAvatar.swift`: затемнённая, с карандашом, и меню по
 * касанию. Экраны правки профиля и чата показывают её одинаково. Карандаш и говорит, что на картинку
 * вообще можно нажать: без него крупное лицо наверху экрана — то, на что смотрят.
 *
 * [canRemove] — есть ли что убирать: без картинки «Удалить» ничего бы не сделало и не предлагается.
 */
@Composable
fun EditableAvatar(
    name: String,
    url: String?,
    seed: String?,
    canRemove: Boolean,
    enabled: Boolean,
    onChange: () -> Unit,
    onRemove: () -> Unit,
    shape: AvatarShape = AvatarShape.CIRCLE,
    size: Dp = Theme.Size.avatarProfile,
) {
    var menu by remember { mutableStateOf(false) }
    val description = stringResource(R.string.picture_edit)
    Box {
        Box(
            Modifier
                .size(size)
                .clip(shape.shape(size))
                .clickable(enabled = enabled, onClickLabel = description) { menu = true }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Avatar(name, url, size, shape = shape, seed = seed)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = Theme.Backdrop.EDITABLE)))
            Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.picture_change)) },
                leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                onClick = {
                    menu = false
                    onChange()
                },
            )
            if (canRemove) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.picture_remove), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        onRemove()
                    },
                )
            }
        }
    }
}
