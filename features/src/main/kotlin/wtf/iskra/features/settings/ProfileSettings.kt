package wtf.iskra.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.AccountActionFailure
import wtf.iskra.core.session.UserSession
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.support.EditableAvatar
import wtf.iskra.features.support.rememberAvatarPicker

/**
 * Своя картинка и своё имя — на экране, который для этого и открывают (`ProfileEditView` на iOS).
 *
 * Кнопки «Сохранить» нет: имя уходит, когда поле *оставили* — «Готово» на клавиатуре, касание мимо,
 * уход с экрана. Имя, которое правится только на экране, открытом нарочно, не сотрёт случайное касание
 * на экране, который читают.
 *
 * Без сети здесь всё выключено: каждое действие — вызов сервера, за которым ничего не стоит в
 * очереди, и имя, набранное без сети, выглядело бы принятым, а потом тихо пропало бы.
 */
@Composable
internal fun ProfileEdit(session: UserSession) {
    val profile by session.profile.collectAsStateWithLifecycle()
    val rooms by session.rooms.state.collectAsStateWithLifecycle()
    val offline = rooms.isUnreachable
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var failure by remember { mutableStateOf<AccountActionFailure?>(null) }
    LaunchedEffect(session) { session.loadProfile() }

    var draft by rememberSaveable { mutableStateOf(profile.displayName.orEmpty()) }
    var naming by remember { mutableStateOf(false) }
    // Имя с сервера (или вернувшееся после отказа) — в поле, но не поверх того, что сейчас набирают.
    LaunchedEffect(profile.displayName) { if (!naming) draft = profile.displayName.orEmpty() }
    val latest by rememberUpdatedState(draft)
    val stillNaming by rememberUpdatedState(naming)
    // Уход с экрана — тоже «оставили поле». `rename` ничего не шлёт, если имя не менялось.
    DisposableEffect(session) { onDispose { if (stillNaming) session.renameInBackground(latest) } }

    val pick = rememberAvatarPicker(
        onCropped = { jpeg -> scope.launch { session.setPicture(jpeg)?.let { failure = it } } },
        onUnreadable = { failure = AccountActionFailure.PICTURE },
    )

    Box(Modifier.fillMaxWidth().padding(Theme.Spacing.generous), contentAlignment = Alignment.Center) {
        EditableAvatar(
            name = profile.displayName ?: session.userId,
            url = profile.avatarUrl,
            seed = session.userId,
            canRemove = profile.avatarUrl != null,
            enabled = !offline,
            onChange = pick,
            onRemove = { scope.launch { session.removePicture()?.let { failure = it } } },
        )
    }

    SectionTitle(R.string.account_name_header)
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        placeholder = { Text(stringResource(R.string.account_name_placeholder)) },
        singleLine = true,
        enabled = !offline,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Theme.Spacing.roomy)
            .onFocusChanged { state ->
                if (naming && !state.isFocused) scope.launch { session.rename(draft)?.let { failure = it } }
                naming = state.isFocused
            },
    )
    // Новое имя — событие в каждом чате аккаунта: все, с кем он переписывается, увидят «X теперь Y».
    // Сказать об этом стоит до того, как поле оставят, а не после.
    Footer(if (offline) R.string.account_edit_offline else R.string.account_name_change_notice)

    // Matrix ID — рядом с именем, как его брат, и неизменяемый: его не поменяет ни один сервер.
    SectionTitle(R.string.account_username_header)
    OutlinedTextField(
        value = session.userId,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Theme.Spacing.roomy),
    )
    Footer(R.string.account_username_fixed)

    failure?.let { shown ->
        AlertDialog(
            onDismissRequest = { failure = null },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(shown.explanation)) },
        )
    }
}
