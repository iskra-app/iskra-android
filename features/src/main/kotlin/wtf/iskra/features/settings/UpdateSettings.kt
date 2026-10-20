package wtf.iskra.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import wtf.iskra.core.support.AppSettings
import wtf.iskra.core.update.UpdateOffer
import wtf.iskra.design.LocalAccent
import wtf.iskra.features.R
import wtf.iskra.features.update.Updates
import wtf.iskra.features.update.needsReleasesPage
import wtf.iskra.features.update.offerText
import wtf.iskra.features.update.releasesPage

/**
 * «Проверять обновления» в «О приложении» (docs/PROMPT.md, 19.1): переключатель с подписью, что и куда
 * уходит, и «Проверить сейчас» с ответом словами. Нашлась версия — нажатие открывает лист обновления.
 */
@Composable
internal fun UpdateChecks(settings: AppSettings, updates: Updates, onOpenUpdate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled by settings.updateChecks.collectAsStateWithLifecycle()
    val offer by updates.checker.offer.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    val status: String? = when {
        checking -> stringResource(R.string.update_checking)
        failed -> stringResource(R.string.update_check_failed)
        else -> when (val current = offer) {
            null -> null
            is UpdateOffer.Available -> stringResource(R.string.update_available, current.versionName)
            else -> offerText(current)?.let { (text, version) -> if (version != null) stringResource(text, version) else stringResource(text) }
        }
    }

    SectionTitle(R.string.update_check)
    Group(
        *buildList<@Composable () -> Unit> {
            add { Toggle(R.string.update_check, R.string.update_check_help, enabled, settings::setUpdateChecks) }
            add {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.update_check_now)) },
                    supportingContent = status?.let { { Text(it) } },
                    modifier = Modifier.clickable(enabled = !checking) {
                        if (offer is UpdateOffer.Available) {
                            onOpenUpdate()
                            return@clickable
                        }
                        // Ручная проверка — решение человека в момент нажатия; при мосте (этап 9) здесь
                        // будет вопрос «проверить напрямую?».
                        checking = true
                        failed = false
                        scope.launch {
                            failed = runCatching { updates.checker.checkNow() }.isFailure
                            checking = false
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = LocalAccent.current.control),
                )
            }
            if (needsReleasesPage(offer)) {
                add {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.update_open_page)) },
                        modifier = Modifier.clickable { context.startActivity(releasesPage()) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = LocalAccent.current.control),
                    )
                }
            }
        }.toTypedArray(),
    )
}
