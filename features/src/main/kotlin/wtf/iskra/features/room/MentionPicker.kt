package wtf.iskra.features.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import wtf.iskra.core.timeline.MentionCandidate
import wtf.iskra.design.Theme
import wtf.iskra.features.support.Avatar

/**
 * Кого можно позвать — над полем ввода, пока набирается «@…» (`MentionPicker` на iOS). Лицо, имя и
 * Matrix ID под ним: двух Саш в комнате различает только он.
 */
@Composable
internal fun MentionPicker(members: List<MentionCandidate>, onPick: (MentionCandidate) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        members.forEach { member ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(member) }
                    .padding(horizontal = Theme.Spacing.roomy, vertical = Theme.Spacing.close),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
            ) {
                Avatar(member.name, member.avatarUrl, Theme.Size.avatarSmall, seed = member.userId)
                Column {
                    Text(member.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(member.userId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
