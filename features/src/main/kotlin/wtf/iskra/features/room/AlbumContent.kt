package wtf.iskra.features.room

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.design.AlbumMosaic
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Фото и видео, отправленные вместе, — одной мозаикой (`Album` решает какие, `AlbumMosaic` — куда
 * каждое). Порт `AlbumBubble`. Каждая плитка открывает просмотрщик на себе, с остальными фото чата
 * по бокам: смахнув с третьего фото альбома, попадаешь на четвёртое.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumContent(
    items: List<Message>,
    onOpen: (String) -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width = Theme.Size.picture
    val frames = remember(items) {
        AlbumMosaic.frames(
            ratios = items.map { (it.kind.picture?.aspectRatio ?: 1.0).toFloat() },
            width = width.value,
            spacing = Theme.Spacing.albumGap.value,
            maxHeight = Theme.Size.pictureMaxHeight.value,
        )
    }
    val height = (frames.maxOfOrNull { it.bottom } ?: 0f).dp
    val open = stringResource(R.string.message_media_open_full_screen)
    Box(modifier.size(width, height).clip(RoundedCornerShape(Theme.Radius.picture))) {
        items.zip(frames).forEach { (message, frame) ->
            val attachment = message.kind.picture ?: return@forEach
            val tile = maxOf(frame.width, frame.height).dp
            val thumbnail = rememberThumbnailState(attachment, tile, obeysAutoDownload = true)
            val image = thumbnail.image
            Box(
                Modifier
                    .offset(frame.x.dp, frame.y.dp)
                    .size(frame.width.dp, frame.height.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(
                        onClickLabel = open,
                        onClick = { onOpen(message.id) },
                        onDoubleClick = onDoubleTap,
                        onLongClick = onLongPress,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (image != null) Image(image, contentDescription = attachment.caption, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (thumbnail.waiting) DownloadPlate(thumbnail, attachment.mediaId, Modifier.fillMaxSize())
                if (!thumbnail.waiting && message.kind is MessageKind.Video) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(Theme.Size.voiceButton).clip(CircleShape).background(Color.Black.copy(alpha = Theme.Backdrop.DIM)),
                    )
                }
            }
        }
    }
}
