package wtf.iskra.features.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import wtf.iskra.core.search.SearchHit
import wtf.iskra.core.timeline.TimelineStore
import androidx.compose.material.icons.filled.Search
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.rooms.RoomTime
import java.time.Instant
import java.time.ZoneId

/**
 * Поиск внутри открытого чата (`RoomSearchView`): поле и находки; нажатие закрывает поиск и ведёт к
 * сообщению — тот же переход, что у цитаты и у общего поиска. Тот же ответ на вопрос поуже: не «в
 * каком чате», а «где в этом».
 */
@Composable
internal fun RoomSearch(store: TimelineStore, onOpen: (String) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            hits = emptyList()
            asked = false
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        searching = true
        hits = store.search(query)
        searching = false
        asked = true
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.close),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
                ) {
                    wtf.iskra.design.PillField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.room_search_prompt),
                        leading = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.weight(1f).focusRequester(focus),
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.room_search_close))
                    }
                }
                when {
                    searching && hits.isEmpty() -> Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LocalAccent.current.control)
                    }
                    asked && hits.isEmpty() -> Text(
                        stringResource(R.string.room_search_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().padding(Theme.Spacing.generous),
                    )
                    else -> {
                        val now = Instant.now()
                        val locale = LocalConfiguration.current.locales[0]
                        LazyColumn {
                            items(hits, key = { it.id }) { hit ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onOpen(hit.id) }
                                        .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(hit.senderName, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.weight(1f))
                                        Text(
                                            RoomTime.label(hit.timestamp, now, ZoneId.systemDefault(), locale),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(hit.excerpt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val DEBOUNCE_MS = 200L
