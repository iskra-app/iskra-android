package wtf.iskra.features.room

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
@Composable
internal fun PendingAttachments(attachments: List<Uri>, onRemove: (Uri) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        contentPadding = PaddingValues(horizontal = Theme.Spacing.close),
        modifier = Modifier.fillMaxWidth().padding(bottom = Theme.Spacing.tight),
    ) {
        items(attachments, key = { it.toString() }) { uri ->
            val image = rememberPickedThumbnail(uri)
            Box(Modifier.size(Theme.Size.galleryThumbnailCurrent).clip(RoundedCornerShape(Theme.Radius.control)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (image != null) Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
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
                        .clickable { onRemove(uri) },
                )
            }
        }
    }
}

@Composable
private fun rememberPickedThumbnail(uri: Uri): ImageBitmap? {
    val context = LocalContext.current
    val side = with(LocalDensity.current) { Theme.Size.galleryThumbnailCurrent.roundToPx() }
    var image by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        image = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(side, side), null).asImageBitmap() }.getOrNull()
        }
    }
    return image
}

/** Выбранный файл целиком и его имя. Null — система его уже не отдаёт (удалили, отозвали доступ). */
internal suspend fun readPicked(context: Context, uri: Uri): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
    try {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        bytes to name
    } catch (error: Exception) {
        Log.error(Log.Category.MEDIA, "выбранное не прочиталось: ${Log.describe(error)}")
        null
    }
}
