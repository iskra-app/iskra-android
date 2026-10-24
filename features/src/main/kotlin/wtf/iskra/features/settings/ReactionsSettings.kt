package wtf.iskra.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.pluralStringResource
import kotlinx.coroutines.launch
import wtf.iskra.core.stickers.Sticker
import wtf.iskra.core.stickers.StickerLibrary
import wtf.iskra.features.room.StickerImage
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.support.AppSettings
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.room.EmojiPanel

/** Пять быстрых эмодзи, которые выбираются нажатием (как в панели над сообщением), — и любой другой за «+». */
private val SHORTCUTS = listOf("❤️", "👍", "😂", "😮", "😢")

/**
 * «Быстрая реакция» (`ReactionsView`): что оставляет двойное нажатие на сообщение. Пишется сразу, без «Готово». Выбран не из пяти —
 * он занимает пятое место подсвеченным, чтобы у настройки всегда было видимое значение; «+» открывает ту же панель эмодзи, что над
 * сообщением, поэтому здесь нельзя выбрать то, чем нельзя отреагировать.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionsPage(settings: AppSettings, stickers: StickerLibrary? = null) {
    val reaction by settings.quickReaction.collectAsStateWithLifecycle()
    val recent by settings.recentReactions.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val row = if (reaction in SHORTCUTS) SHORTCUTS else SHORTCUTS.dropLast(1) + reaction

    SectionTitle(R.string.reactions_quick)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
    ) {
        row.forEach { emoji ->
            val chosen = emoji == reaction
            Box(
                Modifier
                    .size(Theme.Size.swatch)
                    .clip(CircleShape)
                    .then(if (chosen) Modifier.background(accent.control.copy(alpha = CHOSEN_TINT), CircleShape).border(Theme.Border.hairline, accent.control, CircleShape) else Modifier)
                    .clickable(role = Role.RadioButton) { settings.setQuickReaction(emoji) },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        }
        Box(
            Modifier
                .size(Theme.Size.swatch)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = MORE_TINT), CircleShape)
                .clickable(role = Role.Button) { picking = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.reactions_more), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Text(
        stringResource(R.string.reactions_quick_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Theme.Spacing.roomy),
    )
    if (stickers != null) StickersSections(settings, stickers)
    if (picking) {
        Dialog(onDismissRequest = { picking = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().clickable { picking = false }) {
                // Выбор настройки — не реакция: на «недавние» он не влияет, и подсвечивать «свои» нечего.
                EmojiPanel(recent = recent, mine = emptySet(), onPick = { settings.setQuickReaction(it); picking = false }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * Стикеры (`StickerSettingsSections`): что показывает лист — «Искорки», «Мои», пак открытого чата; подписки на паки чатов с «Убрать»;
 * свои стикеры, долгое нажатие убирает; «Очистить недавние». Показ — на устройстве, паки — в аккаунте.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun StickersSections(settings: AppSettings, library: StickerLibrary) {
    val state by library.state.collectAsStateWithLifecycle()
    val builtIn by settings.stickersBuiltIn.collectAsStateWithLifecycle()
    val mine by settings.stickersMine.collectAsStateWithLifecycle()
    val roomPack by settings.stickersRoomPack.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var removing by remember { mutableStateOf<Sticker?>(null) }
    LaunchedEffect(library) { library.load(null) }

    SectionTitle(R.string.stickers_settings_sheet_section)
    Toggle(R.string.stickers_settings_built_in, null, builtIn, settings::setStickersBuiltIn)
    Toggle(R.string.stickers_settings_mine, null, mine, settings::setStickersMine)
    Toggle(R.string.stickers_settings_room_packs, R.string.stickers_settings_room_packs_help, roomPack, settings::setStickersRoomPack)

    val subscriptions = state.packs.filter { it.isRoomPack && library.isSubscribed(it) }
    if (subscriptions.isNotEmpty()) {
        SectionTitle(R.string.stickers_settings_subscriptions)
        subscriptions.forEach { pack ->
            ListItem(
                headlineContent = { Text(pack.name ?: stringResource(R.string.stickers_room_pack)) },
                supportingContent = { Text(pluralStringResource(R.plurals.stickers_settings_count, pack.stickers.size, pack.stickers.size)) },
                leadingContent = { StickerImage(pack.iconUrl, Theme.Size.stickerTab) },
                trailingContent = { TextButton(onClick = { scope.launch { library.unsubscribe(pack) } }) { Text(stringResource(R.string.stickers_settings_unsubscribe), color = MaterialTheme.colorScheme.error) } },
                colors = rowColors(),
            )
        }
        Text(stringResource(R.string.stickers_settings_subscriptions_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Theme.Spacing.roomy))
    }

    SectionTitle(R.string.stickers_mine)
    val own = state.packs.firstOrNull { it.isMine }
    when {
        own != null -> FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            own.stickers.forEach { sticker ->
                Box(
                    Modifier.size(Theme.Size.stickerCell).combinedClickable(onClick = {}, onLongClick = { removing = sticker }),
                    contentAlignment = Alignment.Center,
                ) { StickerImage(sticker.url, Theme.Size.stickerCell, description = sticker.body) }
            }
        }
        state.hasLoaded -> Text(stringResource(R.string.stickers_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Theme.Spacing.roomy))
        else -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.roomy), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalAccent.current.control) }
    }
    Text(stringResource(R.string.stickers_settings_mine_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Theme.Spacing.roomy))

    TextButton(enabled = state.recent.isNotEmpty(), onClick = { scope.launch { library.clearRecent() } }, modifier = Modifier.padding(horizontal = Theme.Spacing.close)) {
        Text(stringResource(R.string.stickers_settings_clear_recent), color = if (state.recent.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.stickers_settings_clear_recent_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close))

    removing?.let { sticker ->
        AlertDialog(
            onDismissRequest = { removing = null },
            confirmButton = { TextButton(onClick = { removing = null; scope.launch { library.remove(sticker) } }) { Text(stringResource(R.string.stickers_remove), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.poll_cancel)) } },
        )
    }
    state.failure?.let { failure ->
        AlertDialog(
            onDismissRequest = library::clearFailure,
            confirmButton = { TextButton(onClick = library::clearFailure) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(failure.explanation)) },
        )
    }
}

private const val CHOSEN_TINT = 0.18f
private const val MORE_TINT = 0.08f
