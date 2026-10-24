package wtf.iskra.features.room

import android.content.Context
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.Log
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Выбранное, но ещё не отправленное (`PendingAttachment`): миниатюры с крестиком над полем ввода.
 * Уходит по кнопке отправки — вложения вперёд, и слова из поля — подписью под первым.
 *
 * Здесь только адреса (`content://` от системного выбора), не байты: двенадцать мегапикселей ждут
 * в галерее, пока их не отправят, а не в памяти, пока человек думает.
 */
/**
 * Выбранное: адрес и что это — фото и видео идут через подготовку (сжатие, очистка), файл уходит как есть.
 */
internal data class PendingAttachment(val uri: Uri, val kind: Kind) {
    enum class Kind { PHOTO, VIDEO, FILE }

    val isFile: Boolean get() = kind == Kind.FILE

    companion object {
        /** Из общего меню: картинка и видео идут через подготовку, всё прочее — файлом как есть. */
        fun shared(context: Context, uri: Uri): PendingAttachment {
            val type = context.contentResolver.getType(uri).orEmpty()
            return PendingAttachment(uri, when {
                type.startsWith("video/") -> Kind.VIDEO
                type.startsWith("image/") && type != "image/svg+xml" -> Kind.PHOTO
                else -> Kind.FILE
            })
        }

        /** Фото или видео — по типу, который называет тот, кто отдал адрес. */
        fun visual(context: Context, uri: Uri): PendingAttachment =
            PendingAttachment(uri, if (context.contentResolver.getType(uri)?.startsWith("video/") == true) Kind.VIDEO else Kind.PHOTO)
    }
}

@Composable
internal fun PendingAttachments(attachments: List<PendingAttachment>, onRemove: (PendingAttachment) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        contentPadding = PaddingValues(horizontal = Theme.Spacing.close),
        modifier = Modifier.fillMaxWidth().padding(bottom = Theme.Spacing.tight),
    ) {
        items(attachments, key = { it.uri.toString() }) { attachment ->
            val uri = attachment.uri
            Box(
                Modifier
                    .then(if (attachment.isFile) Modifier.widthIn(max = Theme.Size.menuWidth) else Modifier.size(Theme.Size.galleryThumbnailCurrent))
                    .height(Theme.Size.galleryThumbnailCurrent)
                    .clip(RoundedCornerShape(Theme.Radius.control))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (attachment.isFile) {
                    // Файл — словом: значок и имя, картинки у него нет.
                    Row(
                        Modifier.fillMaxHeight().padding(start = Theme.Spacing.close, end = Theme.Size.audioScrubber + Theme.Spacing.close),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(rememberPickedName(uri), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    val image = rememberPickedThumbnail(uri, attachment.kind == PendingAttachment.Kind.VIDEO)
                    if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Видео отличается от фото значком — миниатюра одна и та же: кадр.
                    if (attachment.kind == PendingAttachment.Kind.VIDEO) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = Theme.Backdrop.PLATE)),
                        )
                    }
                }
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.room_remove_attachment),
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Theme.Spacing.withinRun)
                        .size(Theme.Size.audioScrubber)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = Theme.Backdrop.PLATE))
                        .clickable { onRemove(attachment) },
                )
            }
        }
    }
}

@Composable
private fun rememberPickedThumbnail(uri: Uri, isVideo: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val side = with(LocalDensity.current) { Theme.Size.galleryThumbnailCurrent.roundToPx() }
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        image = withContext(Dispatchers.IO) {
            // Галерея отдаёт миниатюру сама; снятое камерой и вставленное лежат у нас (`FileProvider`),
            // и миниатюру им делаем сами — кадр видео или уменьшенное фото.
            runCatching { context.contentResolver.loadThumbnail(uri, Size(side, side), null).asImageBitmap() }.getOrNull()
                ?: runCatching { ownThumbnail(context, uri, side, isVideo)?.asImageBitmap() }.getOrNull()
        }
    }
    return image
}

private fun ownThumbnail(context: Context, uri: Uri, side: Int, isVideo: Boolean): Bitmap? =
    if (isVideo) {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, side, side)
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

/** Имя выбранного — для подписи файла и для имени отправляемого видео. */
internal suspend fun pickedName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) { displayName(context, uri) }

@Composable
private fun rememberPickedName(uri: Uri): String {
    val context = LocalContext.current
    var name by remember(uri) { mutableStateOf("") }
    LaunchedEffect(uri) { name = withContext(Dispatchers.IO) { displayName(context, uri) } }
    return name
}

private fun displayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

/** Что за вещь уходит и сколько в ней байт — чтобы решить, следовать ли за отправкой (`MediaSendJobs.isWorthFollowing`). */
internal fun pickedItem(context: Context, attachment: PendingAttachment): wtf.iskra.core.media.MediaSendJobs.Item {
    val bytes = runCatching {
        context.contentResolver.query(attachment.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        }
    }.getOrNull() ?: 0L
    return wtf.iskra.core.media.MediaSendJobs.Item(
        isVideo = attachment.kind == PendingAttachment.Kind.VIDEO,
        isFile = attachment.isFile,
        bytes = bytes,
    )
}

/** Выбранный файл целиком и его имя. Null — система его уже не отдаёт (удалили, отозвали доступ). */
internal suspend fun readPicked(context: Context, uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
    try {
        val name = displayName(context, uri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        bytes to name
    } catch (error: Exception) {
        Log.error(Log.Category.MEDIA, "выбранное не прочиталось: ${Log.describe(error)}")
        null
    }
}
