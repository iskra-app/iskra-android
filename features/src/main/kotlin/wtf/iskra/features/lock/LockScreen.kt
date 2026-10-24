package wtf.iskra.features.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import wtf.iskra.core.security.AppLock
import wtf.iskra.design.PrimaryActionButton
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Замок и то, чем его открывают, — от `MainActivity`, у которой есть окно для `BiometricPrompt`. Экраны знают только это: открыт ли замок,
 * можно ли вообще проверить (настройка не предлагает замок, который устройству нечем открыть) и как попросить проверку.
 */
class LockHooks(
    val appLock: AppLock,
    /** Есть ли у устройства хоть какая-то защита, которой можно проверить: отпечаток, лицо или пин. */
    val canAuthenticate: () -> Boolean,
    /** Просит у системы доказать, что это владелец; [onResult] — true только при успехе. Закрывает щит сам [AppLock]. */
    val authenticate: (onResult: (Boolean) -> Unit) -> Unit,
) {
    companion object {
        /** Замок окна: проверка через `BiometricPrompt`, «Недавние» без содержимого. Зовёт `MainActivity` один раз. */
        fun of(activity: androidx.fragment.app.FragmentActivity, appLock: AppLock): LockHooks {
            LockAuthenticator.keepRecentsPrivate(activity, appLock)
            return LockHooks(
                appLock,
                canAuthenticate = { LockAuthenticator.canAuthenticate(activity) },
                authenticate = { onResult -> LockAuthenticator.authenticate(activity, appLock, onResult) },
            )
        }
    }
}

/** Замок приложения для экранов — null там, где его нет (проба, демо-вход). */
val LocalLock = compositionLocalOf<LockHooks?> { null }

/**
 * Щит, который приложение носит, пока оно заперто. Непрозрачная крышка — под ней ничего не прочесть и не снять скриншотом, — и одно
 * действие: открыть. Просит у системы отпечаток, лицо или пин устройства сразу, как появился, и ещё раз по кнопке, а падает только по «да»
 * системы. Выхода, кроме проверки, нет: «Отмена» здесь была бы замком с чёрным ходом. Порт `LockScreen`.
 */
@Composable
internal fun LockScreen(lock: LockHooks) {
    var asking by remember { mutableStateOf(false) }
    val unlock = {
        if (!asking) {
            asking = true
            lock.authenticate { asking = false }
        }
    }
    // Спросить сразу — кнопка есть повтор, а не первый вопрос.
    LaunchedEffect(Unit) { unlock() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(Theme.Spacing.generous),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Theme.Spacing.generous, Alignment.CenterVertically),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(Theme.Size.avatarLarge),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleMedium)
            PrimaryActionButton(onClick = { unlock() }, enabled = !asking) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
