package wtf.iskra.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/** «Иконка» в «Внешнем виде»: превью восьми, кольцо у той, что включена в лаунчере (`AppIcons`). */
@Composable
internal fun AppIconSection() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIcon.current(context)) }
    var failed by remember { mutableStateOf(false) }
    SectionTitle(R.string.settings_app_icon)
    LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Theme.Spacing.roomy),
        horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.normal),
    ) {
        items(AppIcon.entries, key = { it.alias }) { icon ->
            val chosen = icon == selected
            val shape = RoundedCornerShape(Theme.Radius.appIcon(Theme.Size.appIconTile.value).dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = Theme.Size.appIconTile)) {
                Image(
                    painterResource(icon.preview),
                    contentDescription = stringResource(icon.title),
                    modifier = Modifier
                        .size(Theme.Size.appIconTile)
                        .clip(shape)
                        .border(if (chosen) Theme.Border.thick else Theme.Border.hairline, if (chosen) LocalAccent.current.control else MaterialTheme.colorScheme.outlineVariant, shape)
                        .clickable {
                            if (chosen) return@clickable
                            if (AppIcon.select(context, icon)) selected = icon else failed = true
                        },
                )
                Text(stringResource(icon.title), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Theme.Spacing.tight))
            }
        }
    }
    Text(
        stringResource(R.string.app_icon_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(Theme.Spacing.roomy),
    )
    if (failed) {
        AlertDialog(
            onDismissRequest = { failed = false },
            confirmButton = { TextButton(onClick = { failed = false }) { Text(stringResource(R.string.app_icon_failed_ok)) } },
            text = { Text(stringResource(R.string.app_icon_failed)) },
        )
    }
}
