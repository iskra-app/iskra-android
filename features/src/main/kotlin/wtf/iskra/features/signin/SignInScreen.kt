package wtf.iskra.features.signin

import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import android.graphics.Canvas
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.design.EntryField
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.SecondaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.signin.SignInModel.Intent
import wtf.iskra.features.signin.SignInModel.Step

/** Экран входа. Порт `SignInView.swift`: одна колонка, адрес → дверь → (иногда) форма. */
@Composable
fun SignInScreen(model: SignInModel<*>) {
    val state by model.state.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = Theme.Size.signInColumn)
                .fillMaxWidth()
                .padding(Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.generous),
        ) {
            AnimatedContent(targetState = state.step is Step.Ready, label = "sign-in-step") { ready ->
                if (ready) Form(model, state) else Door(model, state)
            }
        }
    }
}

@Composable
private fun Door(model: SignInModel<*>, state: SignInModel.State) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.generous)) {
        Header()
        if (state.step is Step.Found) Choice(model, state) else Address(model, state)
    }
}

@Composable
private fun Header() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        AppIcon()
        Text(
            stringResource(R.string.sign_in_title),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            stringResource(R.string.sign_in_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = secondary(),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Address(model: SignInModel<*>, state: SignInModel.State) {
    // Открыт ли список серверов и «Что такое Matrix?» — листы поверх входа, не шаги самого входа.
    var choosing by rememberSaveable { mutableStateOf(false) }
    var reading by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal)) {
        Text(stringResource(R.string.sign_in_where_is_account), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.sign_in_where_is_account_help),
            style = MaterialTheme.typography.bodyMedium,
            color = secondary(),
        )
        EntryField(
            value = state.address,
            onValueChange = model::setAddress,
            placeholder = stringResource(R.string.sign_in_address_placeholder),
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { model.findServer() }),
        )
        state.problem?.let { Problem(it) }
        PrimaryActionButton(onClick = model::findServer, enabled = state.canContinue, modifier = Modifier.fillMaxWidth()) {
            BusyLabel(R.string.sign_in_next, spinning = state.isBusy)
        }
        // Помощь тому, кто слова «сервер» не слышал, — кнопкой прямо под полем, а не абзацем над ним.
        SecondaryActionButton(onClick = { choosing = true }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.sign_in_choose_server))
        }
        TextButton(onClick = { reading = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.sign_in_what_is_matrix), style = MaterialTheme.typography.labelLarge, color = secondary())
        }
    }
    if (choosing) ServerPicker(onPick = model::setAddress, onDismiss = { choosing = false })
    if (reading) AboutMatrix(onDismiss = { reading = false })
}

@Composable
private fun Choice(model: SignInModel<*>, state: SignInModel.State) {
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.normal)) {
        state.problem?.let { Problem(it) }
        PrimaryActionButton(onClick = { model.begin(Intent.SIGN_IN) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            BusyLabel(R.string.sign_in_sign_in, spinning = state.pending == Intent.SIGN_IN)
        }
        SecondaryActionButton(onClick = { model.begin(Intent.CREATE_ACCOUNT) }, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
            BusyLabel(R.string.sign_in_create_account, spinning = state.pending == Intent.CREATE_ACCOUNT)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Theme.Spacing.close),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sign_in_server, state.serverName), style = MaterialTheme.typography.bodySmall, color = secondary())
            TextButton(onClick = model::startOver, enabled = !state.isBusy) {
                Text(stringResource(R.string.sign_in_change_server))
            }
        }
    }
}

@Composable
private fun Form(model: SignInModel<*>, state: SignInModel.State) {
    val options = state.options ?: return
    var usingPassword by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy)) {
        TextButton(onClick = { usingPassword = false; model.back() }, enabled = !state.isBusy) {
            Text(stringResource(R.string.sign_in_back))
        }
        Text(
            stringResource(R.string.sign_in_sign_in_to, state.serverName),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (options.canUseSingleSignOn && !(usingPassword && options.supportsPassword)) {
            Text(stringResource(R.string.sign_in_browser_help), style = MaterialTheme.typography.bodyMedium, color = secondary())
            PrimaryActionButton(onClick = model::continueInBrowser, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                BusyLabel(R.string.sign_in_continue_in_browser, spinning = state.isBusy)
            }
            if (options.supportsPassword) {
                TextButton(onClick = { usingPassword = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sign_in_use_password_instead), color = secondary())
                }
            }
        } else if (options.supportsPassword) {
            Text(stringResource(R.string.sign_in_password_help), style = MaterialTheme.typography.bodyMedium, color = secondary())
            EntryField(
                value = state.username,
                onValueChange = model::setUsername,
                placeholder = stringResource(R.string.sign_in_username),
                enabled = !state.isBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
            EntryField(
                value = state.password,
                onValueChange = model::setPassword,
                placeholder = stringResource(R.string.sign_in_password),
                enabled = !state.isBusy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.signInWithPassword() }),
            )
            PrimaryActionButton(onClick = model::signInWithPassword, enabled = state.canSignInWithPassword, modifier = Modifier.fillMaxWidth()) {
                BusyLabel(R.string.sign_in_sign_in, spinning = state.isBusy)
            }
        }
        state.problem?.let { Problem(it) }
    }
}

/** Красная панель с предложением, которое говорит, что делать дальше. Объявляется вслух (live region). */
@Composable
private fun Problem(text: Int) {
    Text(
        stringResource(text),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(Theme.Radius.card))
            .padding(Theme.Spacing.roomy)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun BusyLabel(text: Int, spinning: Boolean) {
    if (spinning) {
        CircularProgressIndicator(
            modifier = Modifier.size(Theme.Size.waveform),
            color = LocalContentColor.current,
            strokeWidth = Theme.Border.thick,
        )
    } else {
        Text(stringResource(text))
    }
}

@Composable
private fun secondary(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Знак Искры над словом «Iskra» — иконка приложения, как она стоит на рабочем столе (`AppIcon.artwork`
 * на iOS): в маске этого устройства и та, что выбрана, когда появятся альтернативные. Слова рядом
 * говорят то же, поэтому для доступности её нет.
 */
@Composable
private fun AppIcon() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val side = with(density) { Theme.Size.signInLogo.roundToPx() }
    val icon = remember(side) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, side, side)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    } ?: return
    Image(icon, contentDescription = null, modifier = Modifier.size(Theme.Size.signInLogo))
}
