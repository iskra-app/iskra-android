package wtf.iskra.features.signin

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * «Не знаете, какой сервер выбрать?» — несколько публичных серверов и, последним, тот, что держишь сам. Выбранный
 * подставляется в поле адреса, и лист закрывается: «Далее» остаётся за человеком. Порт `ServerPickerView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServerPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val locales = LocalConfiguration.current.locales
    val russian = remember(locales) { PublicServer.isRussian((0 until locales.size()).map { locales[it] }) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).widthIn(max = Theme.Size.signInColumn).padding(horizontal = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        ) {
            Text(stringResource(R.string.servers_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.servers_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Theme.Spacing.close),
            )
            PublicServer.all(russian).forEach { server ->
                Card(
                    title = server.address,
                    whereabouts = stringResource(server.whereabouts),
                    about = stringResource(server.about),
                    warning = server.warning?.let { stringResource(it) },
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                ) {
                    onPick(server.address)
                    onDismiss()
                }
            }
            Card(
                title = stringResource(R.string.servers_own_title),
                whereabouts = null,
                about = stringResource(R.string.servers_own_about),
                warning = null,
                icon = Icons.AutoMirrored.Filled.OpenInNew,
            ) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PublicServer.OWN_SERVER_GUIDE)))
                } catch (_: ActivityNotFoundException) {
                    // Браузера нет — нажатию просто нечего открыть.
                }
            }
            Spacer(Modifier.height(Theme.Spacing.generous))
        }
    }
}

@Composable
private fun Card(title: String, whereabouts: String?, about: String, warning: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Theme.Radius.card))
            .clickable(onClick = onClick)
            .padding(Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            whereabouts?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(about, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Theme.Spacing.tight))
            warning?.let {
                Row(Modifier.padding(top = Theme.Spacing.tight), horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * «Что такое Matrix?» — для того, кто слова ни разу не слышал. Первый абзац крупнее: это весь ответ, а три раздела под ним —
 * зачем это важно. Порт `AboutMatrixView`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutMatrix(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).widthIn(max = Theme.Size.signInColumn).padding(horizontal = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.generous),
        ) {
            Text(stringResource(R.string.about_matrix_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.about_matrix_intro), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Section(R.string.about_matrix_email_title, R.string.about_matrix_email_body)
            Section(R.string.about_matrix_nobody_title, R.string.about_matrix_nobody_body)
            Section(R.string.about_matrix_private_title, R.string.about_matrix_private_body)
            Spacer(Modifier.height(Theme.Spacing.generous))
        }
    }
}

@Composable
private fun Section(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
