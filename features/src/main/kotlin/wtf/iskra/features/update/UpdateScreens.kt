package wtf.iskra.features.update

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.update.ApkProblem
import wtf.iskra.core.update.UpdateOffer
import wtf.iskra.core.update.UpdatePolicy
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.SecondaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Строка «Доступна версия 1.4.0» вверху «Настроек» (docs/PROMPT.md, 19.1). **Не** диалог поверх чатов и
 * не уведомление: человек пришёл читать сообщения, а не обновляться. Нажатие открывает [UpdateSheet].
 */
@Composable
fun UpdateRow(offer: UpdateOffer.Available, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.update_available, offer.versionName), fontWeight = FontWeight.SemiBold) },
        leadingContent = { Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = LocalAccent.current.control) },
        modifier = Modifier.clickable(onClick = onOpen),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * Лист обновления: «Что нового» на языке приложения, размер, «Установить» и «Не сейчас» (скрывает эту
 * версию до следующей). Прогресс и отказы — здесь же, словами.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun UpdateSheet(
    updates: Updates,
    offer: UpdateOffer.Available,
    onDismiss: () -> Unit,
    beforeInstall: suspend () -> Unit,
    afterFailure: suspend () -> Unit,
) {
    val context = LocalContext.current
    val install by updates.install.collectAsStateWithLifecycle()
    val busy = install is Updates.Install.Downloading || install == Updates.Install.Verifying || install == Updates.Install.Installing

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Theme.Spacing.generous).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        ) {
            Text(stringResource(R.string.update_available, offer.versionName), style = MaterialTheme.typography.titleLarge)
            offer.notes?.let { notes ->
                Text(stringResource(R.string.update_whats_new), style = MaterialTheme.typography.titleSmall, color = LocalAccent.current.color)
                Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                stringResource(R.string.update_size, Formatter.formatShortFileSize(context, offer.asset.size)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val state = install) {
                is Updates.Install.Downloading -> {
                    Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                }
                Updates.Install.Verifying -> Progress(R.string.update_verifying)
                Updates.Install.Installing -> Progress(R.string.update_installing)
                Updates.Install.NeedsPermission -> {
                    Text(stringResource(R.string.update_permission_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.update_permission_body), style = MaterialTheme.typography.bodyMedium)
                    PrimaryActionButton(onClick = {
                        updates.reset()
                        context.startActivity(updates.permissionSettings())
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_permission_open)) }
                }
                is Updates.Install.Refused -> Failure(problemText(state.problem))
                Updates.Install.DownloadFailed -> Failure(R.string.update_failed_network)
                Updates.Install.InstallFailed -> Failure(R.string.update_failed_install)
                Updates.Install.Idle -> Unit
            }

            if (!busy && install != Updates.Install.NeedsPermission) {
                PrimaryActionButton(
                    onClick = { updates.startInstall(offer, beforeInstall, afterFailure) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_install)) }
                SecondaryActionButton(
                    onClick = {
                        updates.checker.dismiss(offer.versionCode)
                        updates.reset()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.update_not_now)) }
            }
        }
    }
}

@Composable
private fun Progress(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Failure(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private fun problemText(problem: ApkProblem): Int = when (problem) {
    ApkProblem.DAMAGED -> R.string.update_failed_damaged
    ApkProblem.UNREADABLE -> R.string.update_failed_unreadable
    ApkProblem.OTHER_APP -> R.string.update_failed_other_app
    ApkProblem.NOT_NEWER -> R.string.update_failed_not_newer
    ApkProblem.FOREIGN_SIGNATURE -> R.string.update_failed_signature
}

/** Что сказать об исходе проверки, кроме «есть обновление»: оно — строкой в «Настройках». */
fun offerText(offer: UpdateOffer): Pair<Int, String?>? = when (offer) {
    UpdateOffer.UpToDate -> R.string.update_up_to_date to null
    is UpdateOffer.UnsupportedAndroid -> R.string.update_unsupported to offer.versionName
    is UpdateOffer.NoAssetForDevice -> R.string.update_no_asset to offer.versionName
    UpdateOffer.UnknownSchema -> R.string.update_unknown_schema to null
    is UpdateOffer.Available -> null
}

/** Нужна ли ссылка на страницу релиза: файл здесь не подходит, и ставить его надо руками. */
fun needsReleasesPage(offer: UpdateOffer?): Boolean =
    offer is UpdateOffer.NoAssetForDevice || offer == UpdateOffer.UnknownSchema

/** Страница релизов в браузере. */
fun releasesPage(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdatePolicy.RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
