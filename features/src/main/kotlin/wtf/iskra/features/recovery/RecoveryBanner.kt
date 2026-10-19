package wtf.iskra.features.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.session.HistoryProtection
import wtf.iskra.core.session.UserSession
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * «Ваши старые сообщения заперты» над списком (`RecoveryBanner`): у этого устройства нет ключей к
 * истории. Не щит и не тревога — приглашение туда, где их открывают. Нажатие ведёт в ту же настройку
 * восстановления, что после входа.
 */
@Composable
fun RecoveryBanner(session: UserSession, onOpen: () -> Unit) {
    val protection by session.recovery.protection.collectAsStateWithLifecycle()
    if (protection != HistoryProtection.KEY_NEEDED || session.isDemo) return
    val label = stringResource(R.string.recovery_banner_hint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.tight)
            .clip(RoundedCornerShape(Theme.Radius.card))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onOpen)
            .semantics { onClick(label) { onOpen(); true } }
            .padding(Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(stringResource(R.string.recovery_banner_locked), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
