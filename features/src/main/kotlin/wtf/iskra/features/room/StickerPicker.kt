package wtf.iskra.features.room

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.stickers.Sticker
import wtf.iskra.core.stickers.StickerLibrary
import wtf.iskra.core.stickers.StickerPack
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.rememberAvatarImage

/**
 * Стикеры, которые можно отправить, и нажатие, которое отправляет. Порт `StickerPicker.swift`: «недавние»
 * первой секцией, затем каждый пак под своим именем, а полоса картинок паков сверху прыгает к ним.
 *
 * Нажатие отправляет сразу и закрывает лист: стикер — реплика, а не выбор «выбрать, потом отправить».
 * Долгое нажатие предлагает положить в свой пак или убрать из него — тот же выбор, что у стикера в ленте,
 * чтобы найденное у кого-то в комнате можно было оставить себе, не отправив никому.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StickerPicker(
    library: StickerLibrary,
    roomId: String,
    onPick: (Sticker) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by library.state.collectAsStateWithLifecycle()
    LaunchedEffect(library, roomId) { library.load(roomId) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Text(
            stringResource(R.string.stickers_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight),
        )
        when {
            state.packs.isEmpty() && state.recent.isEmpty() ->
                if (state.hasLoaded) Empty() else Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalAccent.current.control)
                }
            else -> Grid(library, state, onPick)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Grid(library: StickerLibrary, state: wtf.iskra.core.stickers.StickerState, onPick: (Sticker) -> Unit) {
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Где в сетке начинается каждая секция: «недавние», затем паки. Вкладки прыгают к этим местам.
    val starts = remember(state.recent, state.packs) {
        buildList {
            var at = 0
            if (state.recent.isNotEmpty()) {
                add(RECENT_ID to at)
                at += 1 + state.recent.size
            }
            state.packs.forEach { pack ->
                add(pack.id to at)
                at += 1 + pack.stickers.size
            }
        }
    }
    var selected by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight),
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            val tabs = buildList<Pair<String, StickerPack?>> {
                if (state.recent.isNotEmpty()) add(RECENT_ID to null)
                state.packs.forEach { add(it.id to it) }
            }
            items(tabs.size) { index ->
                val (id, pack) = tabs[index]
                val on = selected == index
                Box(
                    Modifier.size(Theme.Size.minimumTapTarget)
                        .clip(RoundedCornerShape(Theme.Radius.control))
                        .background(if (on) LocalAccent.current.control.copy(alpha = SELECTED_WASH) else Color.Transparent)
                        .clickable {
                            selected = index
                            scope.launch { grid.animateScrollToItem(starts.first { it.first == id }.second) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        pack == null -> Icon(Icons.Filled.History, contentDescription = stringResource(R.string.stickers_recent))
                        else -> StickerImage(pack.icon?.url, Theme.Size.stickerTab, fallback = true)
                    }
                }
            }
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(Theme.Size.stickerCell),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.recent.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(R.string.stickers_recent), pack = null, library = library, state = state) }
                gridItems(state.recent, key = { "recent/${it.url}" }) { sticker -> Cell(sticker, library, state, onPick) }
            }
            state.packs.forEach { pack ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "header/${pack.id}") { Header(name(pack), pack, library, state) }
                gridItems(pack.stickers, key = { "${pack.id}/${it.url}/${it.shortcode}" }) { sticker -> Cell(sticker, library, state, onPick) }
            }
        }
    }
}

@Composable
private fun name(pack: StickerPack): String = when {
    pack.isMine -> stringResource(R.string.stickers_mine)
    pack.isBuiltIn -> stringResource(R.string.stickers_built_in)
    pack.name != null -> pack.name!!
    else -> stringResource(R.string.stickers_room_pack)
}

/**
 * Название секции. Пак комнаты, на который аккаунт не подписан, предлагает «Добавить к себе»: встретился
 * он здесь, и только здесь иначе пропадёт, когда человек выйдет из чата.
 */
@Composable
private fun Header(title: String, pack: StickerPack?, library: StickerLibrary, state: wtf.iskra.core.stickers.StickerState) {
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().padding(top = Theme.Spacing.normal), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (pack != null && pack.isRoomPack && pack.id !in state.subscribed) {
            TextButton(onClick = { scope.launch { library.subscribe(pack) } }) {
                Icon(Icons.Filled.AddCircle, contentDescription = null)
                Text(stringResource(R.string.stickers_subscribe), modifier = Modifier.padding(start = Theme.Spacing.tight))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(sticker: Sticker, library: StickerLibrary, state: wtf.iskra.core.stickers.StickerState, onPick: (Sticker) -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val mine = sticker.url in state.mine
    Box {
        Box(
            Modifier.size(Theme.Size.stickerCell).combinedClickable(onClick = { onPick(sticker) }, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center,
        ) {
            StickerImage(sticker.url, Theme.Size.stickerCell, description = sticker.body)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (mine) R.string.stickers_remove else R.string.stickers_add)) },
                onClick = {
                    menu = false
                    scope.launch { if (mine) library.remove(sticker) else library.add(sticker) }
                },
            )
        }
    }
}

@Composable
internal fun StickerImage(url: String?, side: androidx.compose.ui.unit.Dp, description: String? = null, fallback: Boolean = false) {
    val image = rememberAvatarImage(url, side)
    when {
        image != null -> Image(image, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.size(side))
        fallback -> Icon(Icons.Filled.Mood, contentDescription = null, modifier = Modifier.size(side))
        else -> Box(Modifier.size(side))
    }
}

/** Отправлять пока нечего — и то единственное, что говорит, как это изменить: лист сам этого не умеет. */
@Composable
private fun Empty() {
    Column(
        Modifier.fillMaxWidth().padding(Theme.Spacing.generous),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Mood, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.stickers_empty_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.stickers_empty_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private const val RECENT_ID = "recent"
private const val SELECTED_WASH = 0.16f
