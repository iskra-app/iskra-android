package wtf.iskra.features.room

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/** «JSON сообщения» (`EventSourceSheet`): событие как прислал сервер, моноширинным шрифтом, с выделением и «Скопировать». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventSourceSheet(json: String?, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.beta_view_source), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { json?.let { clipboard.setText(AnnotatedString(it)) } }, enabled = json != null) { Text(stringResource(R.string.message_menu_copy)) }
        }
        SelectionContainer {
            Text(
                json ?: "",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(Theme.Spacing.roomy),
            )
        }
    }
}
