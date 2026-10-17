package moscow.swag.iskra.features.support

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import moscow.swag.iskra.core.media.MediaLoader
import moscow.swag.iskra.core.rooms.RoomFace
import moscow.swag.iskra.design.Theme
import moscow.swag.iskra.design.blobatar.BlobatarFace
import java.text.BreakIterator

/** Загрузчик медиа вошедшего сеанса. Нет сеанса — нет картинок, и лица рисуются без них. */
val LocalMediaLoader = compositionLocalOf<MediaLoader?> { null }

/** Кто читает: его лицо стоит в кластере группы рядом с остальными. */
val LocalOwnUserId = compositionLocalOf { "" }

/** Два силуэта: круг — человек или чат, скруглённый квадрат — пространство, видно до всяких слов. */
enum class AvatarShape {
    CIRCLE,
    ROUNDED_SQUARE,
    ;

    fun shape(size: Dp): Shape = when (this) {
        CIRCLE -> CircleShape
        ROUNDED_SQUARE -> RoundedCornerShape(size * SPACE_CORNER)
    }
}

/** Скругление пространства — доля размера, чтобы на крупном шрифте квадрат не стал острой плиткой. */
private const val SPACE_CORNER = 0.28f

/** Существо вместо картинки, где её нет. На iOS — переключатель в «Бета» (`BetaFeatures`), по умолчанию включён. */
private const val DRAWS_CREATURES = true

/**
 * Круглое лицо: картинка, если есть; заместитель, пока она не пришла, — и он же навсегда, если её
 * нет. Порт `Avatar.swift`. Заместитель — не спиннер: у большинства людей картинки нет вовсе, и
 * список серых кругов, тихо крутящихся, был бы приложением, изображающим занятость. Поэтому
 * заместитель рисуется сразу как ответ, а фото, придя, сменяет его плавно, не двигая строку.
 */
@Composable
fun Avatar(
    name: String,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: AvatarShape = AvatarShape.CIRCLE,
    /** Из чего растёт существо: Matrix ID или ID комнаты. **Идентификатор, никогда имя.** */
    seed: String? = null,
) {
    val image = rememberAvatarImage(url, size)
    val outline = shape.shape(size)
    val drawsCreature = DRAWS_CREATURES && seed != null && image == null
    Box(
        modifier
            .size(size)
            .clip(outline)
            // Под существом серой подложки нет: оно рисует своё тело и сидит прямо на фоне.
            .then(if (drawsCreature) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceVariant)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = image, animationSpec = tween(Theme.Timing.reaction.inWholeMilliseconds.toInt()), label = "avatar") { loaded ->
            when {
                loaded != null -> Image(loaded, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                drawsCreature && seed != null -> BlobatarFace(seed, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        initials(name),
                        fontSize = initialsSize(size),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Лицо рядом с разговором — три ответа по порядку (`RoomAvatar.swift`): своя картинка комнаты; люди
 * в ней, когда это группа и сервер назвал нескольких; один заместитель для самой комнаты. Личный
 * чат до середины не доходит: он уже взял картинку и сид собеседника в `RoomSummary`.
 */
@Composable
fun RoomAvatar(
    name: String,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: AvatarShape = AvatarShape.CIRCLE,
    seed: String? = null,
    faces: List<RoomFace> = emptyList(),
) {
    val me = LocalOwnUserId.current
    if (url == null && DRAWS_CREATURES && faces.isNotEmpty() && me.isNotEmpty()) {
        // До двух других и сам читающий: группа — это люди, с которыми говоришь *ты*.
        val cluster = faces.filter { it.id != me }.take(2) + RoomFace(me, me)
        FaceCluster(cluster, size, modifier)
    } else {
        Avatar(name, url, size, modifier, shape, seed)
    }
}

/**
 * Двое-трое из группы, внахлёст, как в iMessage: в 28 точках лица не разглядеть, а круги сосчитать
 * может каждый. Двое — по диагонали, трое — треугольником с одним сверху.
 */
@Composable
private fun FaceCluster(faces: List<RoomFace>, size: Dp, modifier: Modifier) {
    val places = if (faces.size == 2) TWO else THREE
    Box(modifier.size(size)) {
        faces.zip(places).forEach { (face, place) ->
            val isCreature = face.avatarUrl == null
            // Существу — слот попросторнее: его тело — около трёх четвертей своего квадрата.
            val inner = size * place.third * (if (isCreature) CREATURE_SLOT else 1f)
            Avatar(
                name = face.name,
                url = face.avatarUrl,
                size = inner,
                seed = face.id,
                modifier = Modifier.offset(x = size * place.first - inner / 2, y = size * place.second - inner / 2),
            )
        }
    }
}

private val TWO = listOf(Triple(0.31f, 0.33f, 0.72f), Triple(0.69f, 0.67f, 0.72f))
private val THREE = listOf(Triple(0.5f, 0.26f, 0.64f), Triple(0.26f, 0.73f, 0.64f), Triple(0.74f, 0.73f, 0.64f))
private const val CREATURE_SLOT = 1.12f

/** Буквы — четыре десятых круга. */
@Composable
private fun initialsSize(size: Dp): TextUnit = with(LocalDensity.current) { (size * INITIALS_FRACTION).toSp() }

private const val INITIALS_FRACTION = 0.4f

/** Первая видимая буква первых двух слов — не половина суррогатной пары и не «@» перед ID. */
internal fun initials(name: String): String {
    val words = name.trimStart('#', '@', '!').split(' ', ' ').filter { it.isNotBlank() }.take(2)
    return words.joinToString("") { word ->
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(word)
        word.substring(0, iterator.next().coerceAtLeast(1)).uppercase()
    }
}

/**
 * Картинка по `mxc://`, из памяти или от сервера. Только в памяти, не на диске: аватарки публичны,
 * но единственный кэш приложения — стор SDK, а второй рядом — это каталог чужих лиц в бэкапе,
 * которого никто не собирался делать. Запрос — в пикселях круга, а не в dp: иначе размазня.
 */
@Composable
private fun rememberAvatarImage(url: String?, size: Dp): ImageBitmap? {
    val loader = LocalMediaLoader.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    var image by remember(url) { mutableStateOf(url?.let { AvatarCache.get(it) }) }
    LaunchedEffect(url, loader, pixels) {
        if (url == null || loader == null || image != null) return@LaunchedEffect
        val bytes = runCatching { loader.avatar(url, pixels) }.getOrNull() ?: return@LaunchedEffect
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return@LaunchedEffect
        AvatarCache.put(url, bitmap)
        image = bitmap
    }
    return image
}

/** Уже скачанные лица — на время жизни процесса. */
private object AvatarCache {
    private val images = LruCache<String, ImageBitmap>(LIMIT)
    fun get(url: String): ImageBitmap? = images.get(url)
    fun put(url: String, image: ImageBitmap) {
        images.put(url, image)
    }

    private const val LIMIT = 256
}
