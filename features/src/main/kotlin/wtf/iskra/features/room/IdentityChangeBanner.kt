package wtf.iskra.features.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import wtf.iskra.core.rooms.IdentityChange
import wtf.iskra.design.SecondaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Полоса «у кого-то в этой комнате новые ключи» (`IdentityChangesStore`). Порт `IdentityChangeBanner`.
 *
 * Один человек за раз, самый давний: ответ на одного поднимает следующего. Стопка таких — стена
 * предупреждений, которую никто не читает, а каждое из них — вопрос только к читающему и только об одном
 * человеке. Серьёзный случай — ключи сменились после проверки — окрашен, чтобы их нельзя было спутать
 * с одного взгляда.
 */
@Composable
internal fun IdentityChangeBanner(change: IdentityChange, failed: Boolean, onAcknowledge: () -> Unit) {
    val serious = change.kind == IdentityChange.Kind.CHANGED_AFTER_VERIFICATION
    val scheme = MaterialTheme.colorScheme
    val background = if (serious) scheme.errorContainer else scheme.surfaceContainerLow
    val ink = if (serious) scheme.onErrorContainer else scheme.onSurface
    Row(
        Modifier.fillMaxWidth().background(background).padding(Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
    ) {
        Icon(
            if (serious) Icons.Filled.GppMaybe else Icons.Filled.Shield,
            contentDescription = null,
            tint = if (serious) scheme.error else scheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
            Text(
                stringResource(if (serious) R.string.room_identity_violation_title else R.string.room_identity_changed_title, change.name),
                style = MaterialTheme.typography.titleSmall,
                color = ink,
            )
            Text(
                stringResource(if (serious) R.string.room_identity_violation_body else R.string.room_identity_changed_body),
                style = MaterialTheme.typography.bodySmall,
                color = ink,
            )
            // Не получилось — сказать тут же, под кнопкой: предупреждение осталось, и это не тихий сбой.
            if (failed) {
                Text(stringResource(R.string.room_identity_failed), style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            SecondaryActionButton(onClick = onAcknowledge, modifier = Modifier.padding(top = Theme.Spacing.tight)) {
                Text(stringResource(if (serious) R.string.room_identity_withdraw else R.string.room_identity_understood))
            }
        }
    }
}
