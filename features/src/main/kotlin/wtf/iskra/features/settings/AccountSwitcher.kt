package wtf.iskra.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import wtf.iskra.core.security.AccountRecord
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.RootModel
import wtf.iskra.features.support.Avatar

/** Как назвать аккаунт в списке: именем, под которым он ходит, или Matrix ID. */
internal fun accountName(account: AccountRecord): String = account.displayName?.takeIf { it.isNotBlank() } ?: account.userId

/**
 * Остальные аккаунты устройства — каждый в одно касание — и «Добавить аккаунт» под ними
 * (`AccountSwitcher` на iOS). Сразу под профилем: это тот же вопрос, что отвечает профиль, — *кем*
 * сейчас приложение.
 *
 * Выход из другого аккаунта — кнопкой в строке, не смахиванием: видна и доступна без жеста. Со
 * спросом: чаты уходят с устройства, а зашифрованная переписка вернётся только с кодом.
 */
@Composable
internal fun AccountsSection(
    accounts: RootModel.Accounts,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
    onSignOut: (String) -> Unit,
) {
    var asking by remember { mutableStateOf<AccountRecord?>(null) }
    if (accounts.hasSeveral) {
        Text(
            stringResource(R.string.accounts_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Theme.Spacing.roomy, end = Theme.Spacing.roomy, top = Theme.Spacing.roomy, bottom = Theme.Spacing.close),
        )
    }
    accounts.others.forEach { account ->
        val leaving = account.sessionId in accounts.signingOut
        val hint = stringResource(R.string.accounts_switch_hint)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !leaving) { onSwitch(account.sessionId) }
                .semantics { onClick(label = hint) { onSwitch(account.sessionId); true } }
                .padding(start = Theme.Spacing.roomy, top = Theme.Spacing.close, bottom = Theme.Spacing.close),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
        ) {
            Avatar(accountName(account), account.avatarUrl, Theme.Size.avatar, seed = account.userId)
            Column(Modifier.weight(1f)) {
                Text(accountName(account), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    account.userId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (leaving) {
                CircularProgressIndicator(
                    color = LocalAccent.current.control,
                    modifier = Modifier.padding(end = Theme.Spacing.roomy).size(Theme.Size.menuIcon),
                )
            } else {
                IconButton(onClick = { asking = account }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Logout,
                        contentDescription = stringResource(R.string.accounts_sign_out_of, accountName(account)),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(Theme.Spacing.roomy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
    ) {
        Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = LocalAccent.current.control)
        Text(stringResource(R.string.accounts_add), style = MaterialTheme.typography.bodyLarge, color = LocalAccent.current.control)
    }
    HorizontalDivider()

    asking?.let { account ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.accounts_sign_out_title, accountName(account))) },
            text = { Text(stringResource(R.string.accounts_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    onSignOut(account.sessionId)
                }) { Text(stringResource(R.string.accounts_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.accounts_cancel)) } },
        )
    }
}

/**
 * Аккаунты по долгому нажатию на шестерёнку списка чатов — тот же список, что в настройках, для
 * момента, когда другой аккаунт нужен сейчас, а не через три касания.
 */
@Composable
fun AccountMenu(
    expanded: Boolean,
    accounts: RootModel.Accounts,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onAdd: () -> Unit,
) {
    DropdownMenu(expanded = expanded && accounts.isAvailable, onDismissRequest = onDismiss) {
        accounts.all.forEach { account ->
            val active = account.sessionId == accounts.activeId
            DropdownMenuItem(
                text = { Text(accountName(account), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Avatar(accountName(account), account.avatarUrl, Theme.Size.avatarSmall, seed = account.userId) },
                trailingIcon = if (active) {
                    { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.accounts_current), tint = LocalAccent.current.control) }
                } else {
                    null
                },
                onClick = {
                    onDismiss()
                    onSwitch(account.sessionId)
                },
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.accounts_add)) },
            leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
            onClick = {
                onDismiss()
                onAdd()
            },
        )
    }
}
