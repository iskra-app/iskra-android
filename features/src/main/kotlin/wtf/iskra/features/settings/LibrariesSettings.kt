package wtf.iskra.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Все библиотеки внутри приложения и их лицензии (docs/PROMPT.md, 19.2): список собирает плагин
 * aboutlibraries из того же графа зависимостей, что проверяет licensee, — не руками. Нажатие — текст
 * лицензии. [resource] — `R.raw.aboutlibraries` из `:app`, где плагин его кладёт.
 */
@Composable
internal fun Libraries(resource: Int) {
    val context = LocalContext.current
    val libraries by produceState<List<Library>?>(null, resource) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val json = context.resources.openRawResource(resource).bufferedReader().use { it.readText() }
                Libs.Builder().withJson(json).build().libraries.sortedBy { it.name.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    var open by remember { mutableStateOf<Library?>(null) }

    Text(
        stringResource(R.string.about_libraries_help),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Theme.Spacing.generous),
    )
    val list = libraries
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Group(
        *list.map { library ->
            @Composable {
                ListItem(
                    headlineContent = { Text(library.name) },
                    supportingContent = {
                        Text(listOfNotNull(library.artifactVersion, library.licenses.joinToString { it.name }.ifEmpty { null }).joinToString(" · "))
                    },
                    modifier = Modifier.clickable { open = library },
                    colors = rowColors(),
                )
            }
        }.toTypedArray(),
    )

    open?.let { library ->
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton(onClick = { open = null }) { Text(stringResource(android.R.string.ok)) } },
            title = { Text(library.name) },
            text = {
                val text = library.licenses.mapNotNull { it.licenseContent }.joinToString("\n\n").ifEmpty {
                    library.licenses.joinToString("\n") { it.url ?: it.name }
                }
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}
