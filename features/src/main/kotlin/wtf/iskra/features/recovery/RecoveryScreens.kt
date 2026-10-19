package wtf.iskra.features.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import wtf.iskra.core.session.RecoveryKey
import wtf.iskra.core.session.VerificationStore
import wtf.iskra.design.EntryField
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.SecondaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Настройка восстановления после входа (`RecoverySetupView`). Начало — явный вызов модели в
 * `LaunchedEffect` экрана, который есть только ради этого вопроса; закончив, экран зовёт [onDone].
 */
@Composable
fun RecoverySetupScreen(model: RecoverySetupModel, onDone: () -> Unit) {
    val step by model.step.collectAsStateWithLifecycle()
    val code by model.code.collectAsStateWithLifecycle()
    val failure by model.failure.collectAsStateWithLifecycle()
    val hasOtherDevices by model.hasOtherDevices.collectAsStateWithLifecycle()
    val approvalUrl by model.approvalUrl.collectAsStateWithLifecycle()
    val verificationStep by model.verification.step.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current

    LaunchedEffect(model) { model.begin() }
    // Сбой по дороге к «готово» сначала читают: уйти сразу значило бы закрыть диалог вместе с экраном.
    LaunchedEffect(step, failure) { if (step == RecoverySetupModel.Step.DONE && failure == null) onDone() }
    // Сравнение прошло — устройство подтверждено, дальше решает модель.
    LaunchedEffect(verificationStep) {
        // Не в самом эффекте: после чужой копии `confirmed` включает её (минуты), а закрытый диалог
        // подтверждения сменит шаг и отменил бы эффект посреди включения.
        if (verificationStep == VerificationStore.Step.Succeeded) scope.launch { model.confirmed() }
    }
    LaunchedEffect(approvalUrl) { approvalUrl?.let { runCatching { uri.openUri(it) } } }
    // «Назад» на шаге выбора и ввода возвращает к выбору, а не выпускает из приложения посередине.
    BackHandler(enabled = step == RecoverySetupModel.Step.ENTERING || step == RecoverySetupModel.Step.LOST) { model.backToChoice() }

    Page {
        when (step) {
            RecoverySetupModel.Step.PREPARING, RecoverySetupModel.Step.STARTING_OVER -> Waiting(R.string.recovery_preparing, null)
            RecoverySetupModel.Step.CREATING -> Waiting(R.string.recovery_creating_title, R.string.recovery_creating_message)
            RecoverySetupModel.Step.CODE -> code?.let { RecoveryCode(it, onSaved = model::savedCode) }
            RecoverySetupModel.Step.CHOOSE -> {
                Title(R.string.recovery_choose_title, R.string.recovery_choose_message)
                // Два равных пути: ни один не «основной». Поэтому обе кнопки одного вида.
                SecondaryActionButton(onClick = model::chooseCode, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_choose_have_code))
                }
                SecondaryActionButton(onClick = { scope.launch { model.confirmFromAnotherDevice() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_choose_other_device))
                }
                TextButton(onClick = { scope.launch { model.lostTheCode() } }) {
                    Text(stringResource(R.string.recovery_choose_neither), color = LocalAccent.current.control)
                }
            }
            RecoverySetupModel.Step.ENTERING -> {
                var typed by rememberSaveable { mutableStateOf("") }
                var working by remember { mutableStateOf(false) }
                Title(R.string.recovery_enter_title, R.string.recovery_enter_message)
                EntryField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = stringResource(R.string.encryption_key_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                PrimaryActionButton(
                    onClick = {
                        working = true
                        scope.launch {
                            if (model.unlock(typed)) typed = ""
                            working = false
                        }
                    },
                    enabled = typed.isNotBlank() && !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.recovery_enter_unlock)) }
                TextButton(onClick = { scope.launch { model.lostTheCode() } }) {
                    Text(stringResource(R.string.recovery_enter_lost), color = LocalAccent.current.control)
                }
            }
            RecoverySetupModel.Step.LOST -> if (hasOtherDevices) {
                Title(R.string.recovery_lost_title, R.string.recovery_lost_has_devices)
                PrimaryActionButton(onClick = { scope.launch { model.recoverWithAnotherDevice() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_choose_other_device))
                }
                TextButton(onClick = model::noAccessToOtherDevice) {
                    Text(stringResource(R.string.recovery_lost_no_access), color = LocalAccent.current.control)
                }
            } else {
                Title(R.string.recovery_lost_alone_title, R.string.recovery_lost_alone_message)
                StartFresh { scope.launch { model.startOver() } }
            }
            // Копию сделало другое устройство, и кода к ней нет: «ввести код» не предлагается — вводить нечего.
            RecoverySetupModel.Step.ELSEWHERE -> if (hasOtherDevices) {
                Title(R.string.recovery_elsewhere_title, R.string.recovery_elsewhere_has_devices)
                PrimaryActionButton(
                    onClick = { scope.launch { model.confirmBackupFromAnotherDevice() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.recovery_choose_other_device)) }
                TextButton(onClick = model::noAccessToOtherDevice) {
                    Text(stringResource(R.string.recovery_lost_no_access), color = LocalAccent.current.control)
                }
            } else {
                Title(R.string.recovery_elsewhere_title, R.string.recovery_elsewhere_alone)
                StartFresh { scope.launch { model.startOver() } }
            }
            RecoverySetupModel.Step.PASSWORD -> {
                var password by remember { mutableStateOf("") }
                Title(R.string.recovery_password_title, R.string.recovery_password_message)
                EntryField(
                    value = password,
                    onValueChange = { password = it },
                    placeholder = stringResource(R.string.recovery_password_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                PrimaryActionButton(
                    onClick = {
                        val typed = password
                        password = ""
                        scope.launch { model.confirmWithPassword(typed) }
                    },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.recovery_password_confirm)) }
            }
            RecoverySetupModel.Step.APPROVING -> {
                Title(R.string.recovery_approve_title, R.string.recovery_approve_message)
                SecondaryActionButton(onClick = { scope.launch { model.approved() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_approve_confirm), color = MaterialTheme.colorScheme.error)
                }
                SecondaryActionButton(onClick = { scope.launch { model.abandonStartOver() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_approve_cancel))
                }
            }
            RecoverySetupModel.Step.CONFIRM_NEW -> {
                Title(R.string.recovery_new_title, R.string.recovery_new_message)
                PrimaryActionButton(onClick = { scope.launch { model.confirmNewCode() } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.recovery_new_confirm))
                }
                TextButton(onClick = model::keepOldCode) {
                    Text(stringResource(R.string.recovery_new_keep_old), color = LocalAccent.current.control)
                }
            }
            RecoverySetupModel.Step.DONE -> Unit
        }
    }

    VerificationDialog(model.verification)

    failure?.let {
        AlertDialog(
            onDismissRequest = model::clearFailure,
            confirmButton = { TextButton(onClick = model::clearFailure) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(it.explanation)) },
        )
    }
}

/** «Начать с чистого листа» — и оговорка, что пароль аккаунта тут ни при чём. */
@Composable
private fun StartFresh(onClick: () -> Unit) {
    Text(
        stringResource(R.string.recovery_lost_alone_not_password),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    SecondaryActionButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recovery_lost_alone_start), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = Theme.Size.signInColumn)
                .verticalScroll(rememberScrollState())
                .padding(Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun Title(title: Int, message: Int?) {
    Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    if (message != null) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Waiting(title: Int, message: Int?) {
    CircularProgressIndicator(color = LocalAccent.current.control)
    Title(title, message)
}

/**
 * Код восстановления — один раз. Лист закрывается единственной кнопкой «Я сохранил код»: код живёт
 * только здесь, и уйти, не сохранив его, значит потерять его навсегда.
 */
@Composable
fun RecoveryCode(code: RecoveryKey, onSaved: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    BackHandler {}
    Title(R.string.encryption_save_key, R.string.encryption_save_key_help)
    SelectionContainer {
        Text(
            code.value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Theme.Radius.card))
                .padding(Theme.Spacing.roomy),
        )
    }
    SecondaryActionButton(
        onClick = {
            clipboard.setText(AnnotatedString(code.value))
            copied = true
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (copied) R.string.encryption_key_copied else R.string.encryption_copy_key)) }
    Text(stringResource(R.string.recovery_save_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    PrimaryActionButton(onClick = onSaved, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.recovery_save_confirm)) }
}

/**
 * Подтверждение устройства (`VerificationSheet`) — поверх всего, где бы человек ни был. Ни один из двух
 * ответов не выделен; закрыть мимоходом нельзя: касание мимо ничего не делает, «назад» — отменяет
 * проверку, о чём другое устройство узнаёт. Имена эмодзи не показываются — SDK отдаёт их по-английски.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VerificationDialog(store: VerificationStore) {
    val step by store.step.collectAsStateWithLifecycle()
    val incoming by store.incoming.collectAsStateWithLifecycle()
    val answered by store.hasAnswered.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    if (step == VerificationStore.Step.Idle) return

    val (title, message) = when (val current = step) {
        VerificationStore.Step.Asked -> R.string.verification_sign_in_title to R.string.verification_asked_message
        VerificationStore.Step.Negotiating -> R.string.verification_negotiating_title to R.string.verification_negotiating_message
        VerificationStore.Step.WaitingForOtherDevice -> R.string.verification_waiting_title to R.string.verification_waiting_message
        is VerificationStore.Step.Comparing -> R.string.verification_title to R.string.verification_compare
        VerificationStore.Step.Succeeded -> R.string.verification_done_title to R.string.verification_done_message
        VerificationStore.Step.Failed -> R.string.verification_failed_title to R.string.verification_failed_message
        VerificationStore.Step.Declined -> R.string.verification_declined_title to R.string.verification_declined_message
        VerificationStore.Step.Refused -> R.string.verification_refused_title to R.string.verification_refused_message
        VerificationStore.Step.Idle -> return
    }
    val finished = step == VerificationStore.Step.Succeeded || step == VerificationStore.Step.Failed ||
        step == VerificationStore.Step.Declined || step == VerificationStore.Step.Refused

    AlertDialog(
        // Касание мимо не закрывает: ответ на этот вопрос — не то, что делают случайно.
        onDismissRequest = {},
        title = { Text(stringResource(title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy)) {
                Text(stringResource(message))
                if (step == VerificationStore.Step.Asked) {
                    incoming?.let { request ->
                        Text(request.deviceName ?: stringResource(R.string.verification_unnamed_device), style = MaterialTheme.typography.titleMedium)
                        Text(request.deviceId, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                (step as? VerificationStore.Step.Comparing)?.let { comparing ->
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal, Alignment.CenterHorizontally),
                    ) {
                        comparing.symbols.forEach { Text(it, style = MaterialTheme.typography.displaySmall) }
                    }
                }
                if (step == VerificationStore.Step.Negotiating || step == VerificationStore.Step.WaitingForOtherDevice || answered) {
                    CircularProgressIndicator(color = LocalAccent.current.control)
                }
            }
        },
        confirmButton = {
            when (step) {
                VerificationStore.Step.Asked -> TextButton(onClick = { scope.launch { store.accept() } }, enabled = !answered) {
                    Text(stringResource(R.string.verification_mine))
                }
                is VerificationStore.Step.Comparing -> TextButton(onClick = { scope.launch { store.approve() } }, enabled = !answered) {
                    Text(stringResource(R.string.verification_match))
                }
                else -> if (finished) {
                    TextButton(onClick = store::reset) { Text(stringResource(R.string.verification_close)) }
                }
            }
        },
        dismissButton = {
            when (step) {
                VerificationStore.Step.Asked -> TextButton(onClick = { scope.launch { store.refuse() } }, enabled = !answered) {
                    Text(stringResource(R.string.verification_not_mine))
                }
                is VerificationStore.Step.Comparing -> TextButton(onClick = { scope.launch { store.decline() } }, enabled = !answered) {
                    Text(stringResource(R.string.verification_no_match))
                }
                VerificationStore.Step.Negotiating, VerificationStore.Step.WaitingForOtherDevice ->
                    TextButton(onClick = { scope.launch { store.cancel() } }) { Text(stringResource(R.string.room_list_swipe_cancel)) }
                else -> Unit
            }
        },
    )
}
