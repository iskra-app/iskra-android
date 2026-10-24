package wtf.iskra.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.core.support.AppSettings
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.room.EmojiPanel

/** Пять быстрых эмодзи, которые выбираются нажатием (как в панели над сообщением), — и любой другой за «+». */
private val SHORTCUTS = listOf("❤️", "👍", "😂", "😮", "😢")

/**
 * «Быстрая реакция» (`ReactionsView`): что оставляет двойное нажатие на сообщение. Пишется сразу, без «Готово». Выбран не из пяти —
 * он занимает пятое место подсвеченным, чтобы у настройки всегда было видимое значение; «+» открывает ту же панель эмодзи, что над
 * сообщением, поэтому здесь нельзя выбрать то, чем нельзя отреагировать.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReactionsPage(settings: AppSettings) {
    val reaction by settings.quickReaction.collectAsStateWithLifecycle()
    val recent by settings.recentReactions.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val row = if (reaction in SHORTCUTS) SHORTCUTS else SHORTCUTS.dropLast(1) + reaction

    SectionTitle(R.string.reactions_quick)
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
    ) {
        row.forEach { emoji ->
            val chosen = emoji == reaction
            Box(
                Modifier
                    .size(Theme.Size.swatch)
                    .clip(CircleShape)
                    .then(if (chosen) Modifier.background(accent.control.copy(alpha = CHOSEN_TINT), CircleShape).border(Theme.Border.hairline, accent.control, CircleShape) else Modifier)
                    .clickable(role = Role.RadioButton) { settings.setQuickReaction(emoji) },
                contentAlignment = Alignment.Center,
            ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        }
        Box(
            Modifier
                .size(Theme.Size.swatch)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = MORE_TINT), CircleShape)
                .clickable(role = Role.Button) { picking = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.reactions_more), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Text(
        stringResource(R.string.reactions_quick_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Theme.Spacing.roomy),
    )
    if (picking) {
        Dialog(onDismissRequest = { picking = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().clickable { picking = false }) {
                // Выбор настройки — не реакция: на «недавние» он не влияет, и подсвечивать «свои» нечего.
                EmojiPanel(recent = recent, mine = emptySet(), onPick = { settings.setQuickReaction(it); picking = false }, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

private const val CHOSEN_TINT = 0.18f
private const val MORE_TINT = 0.08f
