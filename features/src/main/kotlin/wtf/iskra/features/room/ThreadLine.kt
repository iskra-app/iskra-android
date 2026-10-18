package wtf.iskra.features.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import wtf.iskra.core.timeline.ThreadSummaryPreview
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * «3 ответа · Аня: последнее ›» под сообщением, у которого есть тред (`ThreadLine.swift`). Нажатие
 * открывает тред. Последний ответ — если SDK его уже знает; иначе только число.
 */
@Composable
internal fun ThreadLine(thread: ThreadSummaryPreview, onOpen: () -> Unit) {
    val accent = LocalAccent.current.control
    val latest = when {
        thread.latestSender != null && thread.latestText != null -> "${thread.latestSender}: ${thread.latestText}"
        else -> thread.latestText
    }
    Row(
        Modifier
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = Theme.Spacing.close, vertical = Theme.Spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
    ) {
        Icon(Icons.Filled.Forum, contentDescription = null, tint = accent, modifier = Modifier.size(Theme.Size.menuIcon))
        Text(
            pluralStringResource(R.plurals.thread_replies, thread.replies, thread.replies),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = accent,
            maxLines = 1,
        )
        if (latest != null) {
            Text(
                latest,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Theme.Size.menuIcon),
        )
    }
}
