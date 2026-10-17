package moscow.swag.iskra.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moscow.swag.iskra.design.AccentTheme
import moscow.swag.iskra.design.AppearanceMode
import moscow.swag.iskra.design.IskraTheme
import moscow.swag.iskra.design.LocalAccent
import moscow.swag.iskra.design.PrimaryActionButton
import moscow.swag.iskra.design.Theme
import moscow.swag.iskra.features.rooms.RoomListScreen
import moscow.swag.iskra.features.signin.SignInScreen
import moscow.swag.iskra.features.support.LocalMediaLoader
import moscow.swag.iskra.features.support.LocalOwnUserId
import moscow.swag.iskra.features.signin.WebAuthentication

/**
 * Корень приложения (`IskraRootView` на iOS). Фазы — из [RootModel]; экраны получают действия от
 * того, кто их открыл, а не закрывают себя сами через глобальный навигатор (docs/PROMPT.md, 15).
 */
@Composable
fun IskraRoot(
    model: RootModel,
    browser: WebAuthentication,
    accent: AccentTheme = AccentTheme.Default,
    appearance: AppearanceMode = AppearanceMode.Default,
) {
    val phase by model.phase.collectAsStateWithLifecycle()
    IskraTheme(accent = accent, appearance = appearance) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val current = phase) {
                RootModel.Phase.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LocalAccent.current.control)
                }

                is RootModel.Phase.SignedOut -> {
                    val signIn = remember(current) { model.signInModel(browser) }
                    Column {
                        if (current.endedRemotely) {
                            Notice(R.string.session_ended_title, R.string.session_ended_message)
                        }
                        SignInScreen(signIn)
                    }
                }

                RootModel.Phase.Offline -> Centered {
                    Notice(R.string.session_offline_title, R.string.session_offline_message)
                    PrimaryActionButton(onClick = model::retry, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.session_try_again))
                    }
                }

                is RootModel.Phase.SignedIn -> CompositionLocalProvider(
                    LocalMediaLoader provides current.session.media,
                    LocalOwnUserId provides current.session.userId,
                ) {
                    RoomListScreen(current.session.rooms, onSignOut = model::signOut)
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = Theme.Size.signInColumn).padding(Theme.Spacing.generous),
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun Notice(title: Int, message: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = Theme.Spacing.generous),
        verticalArrangement = Arrangement.spacedBy(Theme.Spacing.close),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
