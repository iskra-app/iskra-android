package wtf.iskra.features.room

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.timeline.Message
import wtf.iskra.core.timeline.MessageKind
import wtf.iskra.core.timeline.RichText
import wtf.iskra.core.timeline.TimelineRow
import wtf.iskra.core.timeline.TimelineStore
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Что лежит в «О чате» кроме людей. Порт `RoomContentTab`. */
internal enum class RoomContentTab(val title: Int) {
    MEMBERS(R.string.room_tabs_members),
    MEDIA(R.string.room_tabs_media),
    VOICE(R.string.room_tabs_voice),
    VIDEO_NOTES(R.string.room_tabs_video_notes),
    FILES(R.string.room_tabs_files),
    LINKS(R.string.room_tabs_links),
}

/** Полоса вкладок: людей, фото и видео, голосовых, кружков, файлов, ссылок. Прокручивается вбок, когда не помещается. */
@Composable
internal fun RoomContentPicker(tabs: List<RoomContentTab>, selection: RoomContentTab, onSelect: (RoomContentTab) -> Unit) {
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        items(tabs, key = { it.name }) { tab ->
            FilterChip(selected = tab == selection, onClick = { onSelect(tab) }, label = { Text(stringResource(tab.title)) })
        }
    }
}

/**
 * Чем наполнена вкладка — из той же ленты комнаты, что и переписка: сообщения отсортированы по виду. Пока вкладка открыта, лента
 * листается назад сама (до двадцати страниц за заход), чтобы было что показать, как `AutoLoadEarlier`.
 */
@Composable
internal fun RoomContent(tab: RoomContentTab, store: TimelineStore) {
    val state by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(store) { store.start() }
    LaunchedEffect(store, tab) {
        var pages = 0
        while (pages < PAGES_PER_VISIT && store.state.value.canLoadMore && !store.state.value.didFailToPaginate) {
            store.loadMore()
            pages++
        }
    }
    val messages = remember(state.rows) {
        state.rows.asReversed().mapNotNull { (it as? TimelineRow.MessageRow)?.message }.flatMap { it.members }
    }
    when (tab) {
        RoomContentTab.MEMBERS -> Unit
        RoomContentTab.MEDIA -> MediaGrid(messages.filter { it.kind is MessageKind.Image || it.kind is MessageKind.Video })
        RoomContentTab.VOICE -> MessageList(messages.filter { it.kind is MessageKind.Voice || it.kind is MessageKind.Audio })
        RoomContentTab.VIDEO_NOTES -> MessageList(messages.filter { it.kind is MessageKind.VideoNote })
        RoomContentTab.FILES -> MessageList(messages.filter { it.kind is MessageKind.File })
        RoomContentTab.LINKS -> LinkList(linksOf(messages))
    }
    if (state.isPaginating) {
        Box(Modifier.fillMaxWidth().padding(Theme.Spacing.normal), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.padding(Theme.Spacing.close), color = LocalAccent.current.control)
        }
    }
}

private const val PAGES_PER_VISIT = 20
private const val PER_ROW = 3

/** Фото и видео — квадратами по три в ряд, под заголовками месяцев; нажатие открывает галерею. */
@Composable
private fun MediaGrid(items: List<Message>) {
    var viewing by remember { mutableStateOf<String?>(null) }
    val viewable = remember(items) { items.viewable() }
    viewing?.let { id -> MediaViewer(viewable, id, onClose = { viewing = null }) }
    val zone = ZoneId.systemDefault()
    val groups = remember(items) { items.groupBy { YearMonth.from(it.timestamp.atZone(zone)) }.toList() }
    groups.forEach { (month, inMonth) ->
        MonthHeader(month)
        inMonth.chunked(PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Theme.Border.hairline)) {
                row.forEach { message ->
                    val attachment = (message.kind as? MessageKind.Image)?.attachment ?: (message.kind as? MessageKind.Video)?.attachment
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(androidx.compose.ui.graphics.RectangleShape).clickable { viewing = message.id }) {
                        val bitmap = attachment?.let { rememberThumbnail(it, Theme.Size.picture) }
                        if (bitmap != null) {
                            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                        } else {
                            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
                        }
                    }
                }
                // Пустые квадраты держат короткий последний ряд в тех же столбцах.
                repeat(PER_ROW - row.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = Theme.Border.hairline))
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val label = month.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } +
        if (month.year != LocalDate.now().year) " ${month.year}" else ""
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Theme.Spacing.roomy, end = Theme.Spacing.roomy, top = Theme.Spacing.normal, bottom = Theme.Spacing.close),
    )
}

/** Голосовые, кружки и файлы — теми же строками, что в переписке: проигрываются и открываются тут же. */
@Composable
private fun MessageList(items: List<Message>) {
    val accent = LocalAccent.current
    Column(Modifier.padding(horizontal = Theme.Spacing.roomy), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        items.forEach { message ->
            Column {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Theme.Spacing.tight),
                )
                if (message.kind is MessageKind.VideoNote) {
                    MediaContent(message.kind, Modifier, messageId = message.id)
                } else {
                    Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(Theme.Radius.bubble)).background(accent.incomingBubble).padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close)) {
                        AttachmentContent(message, ink = accent.onIncomingBubble, fill = accent.color, onFill = accent.foreground)
                    }
                }
            }
        }
    }
}

/** Адрес и сообщение, откуда он: одно сообщение может нести несколько. */
internal data class LinkItem(val id: String, val message: Message, val url: String)

internal fun linksOf(messages: List<Message>): List<LinkItem> = messages.flatMap { message ->
    val body = when (val kind = message.kind) {
        is MessageKind.Text -> kind.body
        is MessageKind.Notice -> kind.body
        is MessageKind.Emote -> kind.body
        else -> return@flatMap emptyList()
    }
    RichText.of(body, message.formattedBody).spans.mapNotNull { it.style.link }
        .filter { it.startsWith("http://") || it.startsWith("https://") }
        .distinct()
        .map { LinkItem(message.id + "|" + it, message, it) }
}

@Composable
private fun LinkList(items: List<LinkItem>) {
    val handler = LocalUriHandler.current
    Column {
        items.forEach { item ->
            Column(
                Modifier.fillMaxWidth().clickable { runCatching { handler.openUri(item.url) } }
                    .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
            ) {
                Text(item.url, style = MaterialTheme.typography.bodyMedium, color = LocalAccent.current.control, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.message.senderName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}
