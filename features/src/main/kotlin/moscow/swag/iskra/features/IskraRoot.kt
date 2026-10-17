package moscow.swag.iskra.features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import moscow.swag.iskra.design.AccentTheme
import moscow.swag.iskra.design.AppearanceMode
import moscow.swag.iskra.design.IskraTheme
import moscow.swag.iskra.design.PrimaryActionButton
import moscow.swag.iskra.design.Theme

/**
 * Корень приложения (`IskraRootView` на iOS, docs/PROMPT.md, 15).
 *
 * Пока — этап 1: каркас, который запускается на телефоне на семи языках. Фазы (запуск → вход →
 * восстановление → чаты), `RootModel` и восстановление сессии придут с этапом 2. Кнопка входа
 * выключена честно: входа ещё нет, и интерфейс не обещает того, чего нет сейчас.
 */
@Composable
fun IskraRoot(
    accent: AccentTheme = AccentTheme.Default,
    appearance: AppearanceMode = AppearanceMode.Default,
) {
    IskraTheme(accent = accent, appearance = appearance) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(Theme.Spacing.generous),
                verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.sign_in_title),
                    style = MaterialTheme.typography.displaySmall,
                )
                Text(
                    text = stringResource(R.string.sign_in_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                PrimaryActionButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.widthIn(max = Theme.Size.signInColumn).fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sign_in_continue))
                }
            }
        }
    }
}
