package wtf.iskra.features.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import wtf.iskra.core.timeline.PollDraft
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Новый опрос (`PollComposer`): вопрос, варианты (последний пустой добавляется сам, пока не набрано [PollDraft.MAX_ANSWERS]) и две настройки.
 * «Отправить» — когда есть вопрос и два непустых варианта.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PollComposerSheet(onSend: (PollDraft) -> Unit, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(PollDraft()) }
    fun answer(index: Int, text: String) {
        val next = draft.answers.toMutableList().also { it[index] = text }
        // Пишут в последний пустой — под ним появляется следующий: список растёт под рукой, без кнопки.
        if (index == next.lastIndex && text.isNotEmpty() && next.size < PollDraft.MAX_ANSWERS) next += ""
        draft = draft.copy(answers = next)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).imePadding().padding(horizontal = Theme.Spacing.roomy).padding(bottom = Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.poll_cancel)) }
                Text(
                    stringResource(R.string.poll_new_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = { onSend(draft); onDismiss() }, enabled = draft.canSend) { Text(stringResource(R.string.poll_send)) }
            }
            Text(stringResource(R.string.poll_question), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.control)
            OutlinedTextField(
                value = draft.question,
                onValueChange = { draft = draft.copy(question = it) },
                placeholder = { Text(stringResource(R.string.poll_question_placeholder)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.poll_answers), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.control)
            draft.answers.forEachIndexed { index, text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { answer(index, it) },
                        placeholder = { Text(stringResource(R.string.poll_answer_placeholder)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.answers.size > 2) {
                        IconButton(onClick = { draft = draft.copy(answers = draft.answers.toMutableList().also { it.removeAt(index) }) }) {
                            Icon(Icons.Filled.RemoveCircle, contentDescription = stringResource(R.string.poll_remove_answer), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (draft.answers.size < PollDraft.MAX_ANSWERS && draft.answers.last().isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable { draft = draft.copy(answers = draft.answers + "") }.padding(vertical = Theme.Spacing.close),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = LocalAccent.current.control)
                    Text(stringResource(R.string.poll_add_answer), color = LocalAccent.current.control)
                }
            }
            Text(stringResource(R.string.poll_settings), style = MaterialTheme.typography.labelLarge, color = LocalAccent.current.control)
            SwitchRow(R.string.poll_allow_multiple, draft.isMultipleChoice) { draft = draft.copy(isMultipleChoice = it) }
            SwitchRow(R.string.poll_anonymous, draft.isAnonymous) { draft = draft.copy(isAnonymous = it) }
            if (draft.isAnonymous) {
                Text(stringResource(R.string.poll_anonymous_footer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(title), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
