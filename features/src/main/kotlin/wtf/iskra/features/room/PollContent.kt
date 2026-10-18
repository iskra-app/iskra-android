package wtf.iskra.features.room

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import wtf.iskra.core.timeline.Poll
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import java.text.NumberFormat

/**
 * Опрос в переписке: вопрос, ответы и — когда есть что показать — как ответила комната. Порт `PollBubble`.
 *
 * Одно нажатие — голос. Кнопки «Проголосовать» под ответами нет: ответ заменяет прежний, и промах
 * ничего не стоит — нажми другой. В опросе с несколькими ответами нажатие ставит или снимает галочку,
 * и каждая уходит сразу.
 *
 * Полосы скрыты, пока не ответил сам (`Poll.showsResults`): видеть, куда клонится комната, до выбора —
 * ровно то, чего отправитель опроса не хочет.
 */
@Composable
internal fun PollContent(poll: Poll, ink: Color, fill: Color, onVote: (List<String>) -> Unit) {
    val quiet = ink.copy(alpha = QUIET)
    Column(
        Modifier.width(Theme.Size.poll).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight)) {
            Text(header(poll), style = MaterialTheme.typography.labelMedium, color = quiet)
            Text(poll.question, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = ink)
        }
        poll.answers.forEach { answer -> AnswerRow(poll, answer, ink, quiet, fill, onVote) }
        Text(footer(poll), style = MaterialTheme.typography.labelMedium, color = quiet)
    }
}

@Composable
private fun header(poll: Poll): String {
    val kind = stringResource(
        when {
            poll.isEnded -> R.string.poll_ended
            poll.isDisclosed -> R.string.poll_kind
            else -> R.string.poll_kind_anonymous
        },
    )
    return if (poll.isMultipleChoice && !poll.isEnded) {
        stringResource(R.string.poll_header_joined, kind, stringResource(R.string.poll_multiple))
    } else {
        kind
    }
}

@Composable
private fun footer(poll: Poll): String {
    if (poll.voters == 0) return stringResource(R.string.poll_no_votes)
    val count = pluralStringResource(R.plurals.poll_voters, poll.voters, poll.voters)
    return if (poll.showsResults) count else stringResource(R.string.poll_header_joined, count, stringResource(R.string.poll_hidden_until_end))
}

@Composable
private fun AnswerRow(poll: Poll, answer: Poll.Answer, ink: Color, quiet: Color, fill: Color, onVote: (List<String>) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
    val share = poll.share(answer)
    val chosen = stringResource(R.string.poll_chosen)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !poll.isEnded, role = if (poll.isMultipleChoice) Role.Checkbox else Role.RadioButton) {
                poll.choiceAfterTapping(answer.id)?.let(onVote)
            }
            .semantics {
                selected = answer.isChosen
                if (answer.isChosen) stateDescription = chosen
            },
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
            // Круг — один ответ, квадрат — несколько: так у системы. С галочкой — свой.
            Icon(
                when {
                    poll.isMultipleChoice && answer.isChosen -> Icons.Filled.CheckBox
                    poll.isMultipleChoice -> Icons.Filled.CheckBoxOutlineBlank
                    answer.isChosen -> Icons.Filled.CheckCircle
                    else -> Icons.Filled.RadioButtonUnchecked
                },
                contentDescription = null,
                tint = if (answer.isChosen) fill else quiet,
                modifier = Modifier.alpha(if (poll.isEnded && !answer.isChosen) 0f else 1f),
            )
            Text(
                answer.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (answer.isWinner) FontWeight.SemiBold else FontWeight.Normal,
                color = ink,
                modifier = Modifier.weight(1f),
            )
            if (poll.showsResults) Text(percent.format(share), style = MaterialTheme.typography.labelLarge, color = quiet)
        }
        if (poll.showsResults) Bar(share, fill, quiet)
    }
}

/** Полоса доли. У нуля — пустая дорожка, а не точка: «никто» не должно выглядеть «кто-то». */
@Composable
private fun Bar(share: Double, fill: Color, track: Color) {
    Box(Modifier.fillMaxWidth().height(Theme.Size.pollBar).clip(CircleShape).background(track.copy(alpha = TRACK))) {
        if (share > 0) {
            val width: Dp = max(Theme.Size.pollBar, Theme.Size.poll * share.toFloat())
            Box(Modifier.width(width).height(Theme.Size.pollBar).clip(CircleShape).background(fill))
        }
    }
}

private const val QUIET = 0.7f
private const val TRACK = 0.3f
