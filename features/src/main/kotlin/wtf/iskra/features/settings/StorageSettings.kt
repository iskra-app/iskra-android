package wtf.iskra.features.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.storage.StorageKind
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Что приложение держит на устройстве и единственное, что можно выбросить. Порт `StorageView`.
 *
 * Три хранилища показаны порознь, а не одним числом, потому что одно число отвечает не на тот вопрос. «Искра: 640 МБ» говорит удалить
 * приложение, а «скачанное, что вернётся: 480 МБ, ключи, что нет: 4 МБ» — что нажимать. Поэтому у каждой строки — фраза о том, что
 * стоит её потеря, а кнопка — только у того, что можно выбросить. Кнопок две, и это не одна и та же с разной областью: одна о месте и
 * берёт кэш (и индекс с ним), другая берёт один индекс — на случай, о котором не догадаться по слову «кэш»: давно удалённое сообщение могло
 * оставить слова в индексе, и забрать их назад можно только стерев его.
 *
 * После очистки клиент кончен, и приложение открывает сеанс заново ([onCleared]): токены и ключи нетронуты, выхода нет.
 */
@Composable
internal fun StoragePage(session: UserSession, onCleared: () -> Unit) {
    val store = remember(session) { session.storage() }
    val state by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmingCache by remember { mutableStateOf(false) }
    var confirmingSearch by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    // Измеряется при открытии и после очистки: SDK пишет в эти хранилища непрерывно, и раз данный ответ тихо стареет. Раз за визит — честно.
    LaunchedEffect(store) { store.measure() }
    fun size(bytes: Long) = Formatter.formatFileSize(context, bytes)

    Group({
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_total)) },
            leadingContent = { Icon(Icons.Outlined.Storage, contentDescription = null, tint = LocalAccent.current.control) },
            trailingContent = {
                if (state.isMeasuring && state.amounts.isEmpty()) {
                    Text(stringResource(R.string.storage_measuring), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(size(state.total), style = MaterialTheme.typography.titleSmall)
                }
            },
            colors = rowColors(),
        )
    })
    Group(
        *state.amounts.map { amount ->
            @Composable {
                val (title, explained) = when (amount.kind) {
                    StorageKind.SESSION -> R.string.storage_session to R.string.storage_session_explained
                    StorageKind.CACHE -> R.string.storage_cache to R.string.storage_cache_explained
                    StorageKind.SEARCH -> R.string.storage_search to R.string.storage_search_explained
                }
                ListItem(
                    headlineContent = { Text(stringResource(title)) },
                    supportingContent = { Text(stringResource(explained)) },
                    trailingContent = { Text(size(amount.bytes), style = MaterialTheme.typography.titleSmall) },
                    colors = rowColors(),
                )
            }
        }.toTypedArray(),
    )
    // Кнопка называет число, которое вернёт: «очистить кэш», не говорящий, что стоит и что даёт, просит решения без фактов.
    Group({
        ClearRow(
            title = if (state.isClearing) R.string.storage_clearing else R.string.storage_clear,
            amount = size(state.reclaimable),
            busy = state.isClearing,
            enabled = !state.isClearing && state.reclaimable > 0,
        ) { confirmingCache = true }
    })
    // Отдельная секция: она отвечает на другой вопрос. Эта — о месте; та — о слове удалённого сообщения, что индекс мог оставить.
    Group({
        ClearRow(
            title = if (state.isClearing) R.string.storage_clearing else R.string.storage_search_clear,
            amount = size(state.searchIndexBytes),
            busy = false,
            enabled = !state.isClearing && state.searchIndexBytes > 0,
        ) { confirmingSearch = true }
    })

    if (confirmingCache) {
        Confirm(R.string.storage_confirm_title, R.string.storage_confirm_body, onDismiss = { confirmingCache = false }) {
            confirmingCache = false
            scope.launch {
                try {
                    store.clearCache()
                    onCleared()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = R.string.storage_failed
                }
            }
        }
    }
    if (confirmingSearch) {
        Confirm(R.string.storage_search_confirm_title, R.string.storage_search_confirm_body, onDismiss = { confirmingSearch = false }) {
            confirmingSearch = false
            scope.launch {
                try {
                    store.clearSearchIndex()
                    onCleared()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = R.string.storage_search_failed
                }
            }
        }
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

@Composable
private fun ClearRow(title: Int, amount: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator(Modifier.size(Theme.Size.uploadRing), strokeWidth = Theme.Size.waveformBar)
            } else {
                Text(amount, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun Confirm(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.storage_confirm_action), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.storage_cancel)) } },
    )
}
