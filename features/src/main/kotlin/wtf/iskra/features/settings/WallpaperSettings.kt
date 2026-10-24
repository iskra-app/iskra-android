package wtf.iskra.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.support.AppSettings
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.design.Wallpaper
import wtf.iskra.design.WallpaperBackdrop
import wtf.iskra.features.R
import wtf.iskra.features.support.WallpaperStore

/**
 * Обои: ряд высоких плиток — «без», шесть градиентов и своя фотография — и насколько их приглушить. Порт `wallpaperRow` из `AppearanceView`.
 *
 * Рядом, а не сеткой, как цвета, и разница в том, что выбирается: цвет — точка, и восемь точек помещаются в две строки, а обои — *картинка*, и картинка,
 * показанная с такой мелкостью, чтобы восемь влезли в ширину телефона, — картинка, по которой не судят. Поэтому плитки высокие, формы экрана, который они
 * заполнят, а ряд прокручивается. Плитка уже с приглушением, чтобы ползунок под ней был о чём-то.
 */
@Composable
internal fun WallpaperSection(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val name by settings.wallpaper.collectAsStateWithLifecycle()
    val storedDimming by settings.wallpaperDimming.collectAsStateWithLifecycle()
    val photo by WallpaperStore.photo.collectAsStateWithLifecycle()
    val wallpaper = Wallpaper.fromStorage(name)
    val dimming = storedDimming ?: Wallpaper.DEFAULT_DIMMING
    LaunchedEffect(Unit) { WallpaperStore.load(context) }

    // Выбор своей картинки — выбор *файла*: библиотека открывается, и настройка меняется, лишь когда есть на что её менять.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val data = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (data != null && WallpaperStore.store(context, data)) settings.setWallpaper(Wallpaper.PHOTO.storageName)
        }
    }
    val choosePhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    SectionTitle(R.string.appearance_wallpaper)
    LazyRow(
        contentPadding = PaddingValues(horizontal = Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        items(Wallpaper.entries) { choice ->
            val label = stringResource(wallpaperName(choice))
            Tile(
                choice = choice,
                label = label,
                selected = choice == wallpaper,
                photo = photo,
                dimming = dimming,
                needsPhoto = choice == Wallpaper.PHOTO && photo == null,
            ) {
                when {
                    choice == Wallpaper.PHOTO && photo == null -> choosePhoto()
                    else -> settings.setWallpaper(choice.storageName)
                }
            }
        }
    }
    if (wallpaper.isSet) {
        // Мерит ползунок: плитки над ним темнеют, и настройка читается по картинкам, а не по ползунку.
        val lightens = !LocalAccent.current.isDark
        Column(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (lightens) R.string.appearance_wallpaper_lightening else R.string.appearance_wallpaper_dimming),
                    modifier = Modifier.weight(1f),
                )
                Text(java.text.NumberFormat.getPercentInstance().format(dimming.toDouble()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Slider(value = dimming, onValueChange = settings::setWallpaperDimming, valueRange = 0f..Wallpaper.MAXIMUM_DIMMING)
            // Передумать насчёт фотографии — единственных обоев, что можно не только выбрать, но и сменить.
            if (wallpaper == Wallpaper.PHOTO) {
                TextButton(onClick = { choosePhoto() }) { Text(stringResource(R.string.appearance_wallpaper_choose)) }
            }
        }
    }
}

@Composable
private fun Tile(choice: Wallpaper, label: String, selected: Boolean, photo: androidx.compose.ui.graphics.ImageBitmap?, dimming: Float, needsPhoto: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Theme.Radius.picture)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(Theme.Border.hairline, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.size(Theme.Size.wallpaperTile.width, Theme.Size.wallpaperTile.height).clip(shape).clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Обрезано и ограничено здесь, а не доверено плитке: обои на экран рисуются во всю высоту, а миниатюра — нет.
            WallpaperBackdrop(choice, photo, dimming)
            if (choice == Wallpaper.NONE) Icon(Icons.Outlined.Block, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (needsPhoto) Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) {
                // На круге цвета поверхности: синяя галочка на синем море иначе пропадает.
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(Theme.Spacing.close).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = label, tint = LocalAccent.current.control)
                }
            }
        }
    }
}

private fun wallpaperName(choice: Wallpaper): Int = when (choice) {
    Wallpaper.NONE -> R.string.appearance_wallpaper_none
    Wallpaper.SUNSET -> R.string.appearance_wallpaper_sunset
    Wallpaper.SEA -> R.string.appearance_wallpaper_sea
    Wallpaper.FOREST -> R.string.appearance_wallpaper_forest
    Wallpaper.DUSK -> R.string.appearance_wallpaper_dusk
    Wallpaper.SAND -> R.string.appearance_wallpaper_sand
    Wallpaper.NIGHT -> R.string.appearance_wallpaper_night
    Wallpaper.PHOTO -> R.string.appearance_wallpaper_photo
}

