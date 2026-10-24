package wtf.iskra.features.room

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.location.hasLocationPermission
import wtf.iskra.features.map.MapTarget
import wtf.iskra.features.map.PickerMap
import wtf.iskra.features.support.closesOnEscape
import kotlin.time.Duration

/**
 * Выбор места на карте (`LocationPicker`): карта двигается под неподвижным перекрестием, уйдёт то, что под ним. Тапнуть, чтобы поставить
 * булавку, читается хорошо и работает плохо: нужная точка под пальцем в миг, когда решаешь, и выбранного не видно.
 *
 * Разрешение на «где я» спрашивается только когда нажали кнопку, и не при открытии: тот, кто шлёт булавку из чужого города, не должен
 * для этого называть свой. Открывается на мире целиком — или на том, где человек уже был, если разрешение дано. [onShareLive] — «Делиться
 * ещё…» на выбранное время, где комната это умеет.
 */
@Composable
internal fun LocationPicker(
    onSend: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit,
    onShareLive: ((Duration) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var centre by remember { mutableStateOf<MapTarget?>(null) }
    var start by remember { mutableStateOf<MapTarget?>(null) }
    var flyTo by remember { mutableStateOf<MapTarget?>(null) }
    var choosingDuration by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }

    fun findMe() {
        scope.launch { currentLocation(context)?.let { flyTo = MapTarget(it.latitude, it.longitude) } }
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) findMe() else denied = true
    }
    // Уже разрешено — открыться там, где человек; нет — на мире, и ничего не спрашивать.
    LaunchedEffect(Unit) {
        if (hasLocationPermission(context)) currentLocation(context)?.let { start = MapTarget(it.latitude, it.longitude) }
    }

    if (choosingDuration && onShareLive != null) {
        LiveDurationDialog(onPick = onShareLive, onDismiss = { choosingDuration = false })
        return
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().closesOnEscape(onDismiss)) {
            PickerMap(start = start, flyTo = flyTo, onCentre = { lat, lon -> centre = MapTarget(lat, lon) }, modifier = Modifier.fillMaxSize())
            // Перекрестие: булавка стоит остриём на центре, а не серединой значка.
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).size(Theme.Size.crosshair).offset(y = -Theme.Size.crosshair / 2),
            )
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = BAR), modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                Row(Modifier.safeDrawingPadding().padding(horizontal = Theme.Spacing.close), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.location_cancel)) }
                    Text(stringResource(R.string.location_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(enabled = centre != null, onClick = { centre?.let { onSend(it.latitude, it.longitude) } }) { Text(stringResource(R.string.location_send)) }
                }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(Theme.Spacing.roomy),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                if (onShareLive != null) {
                    ExtendedFloatingActionButton(
                        onClick = { choosingDuration = true },
                        icon = { Icon(Icons.Filled.NearMe, contentDescription = null) },
                        text = { Text(stringResource(R.string.location_share_live)) },
                    )
                } else {
                    Box {}
                }
                FloatingActionButton(onClick = { if (hasLocationPermission(context)) findMe() else ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                    Icon(Icons.Filled.MyLocation, contentDescription = stringResource(R.string.location_my_location))
                }
            }
        }
    }
    if (denied) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { denied = false },
            confirmButton = { TextButton(onClick = { denied = false }) { Text(stringResource(android.R.string.ok)) } },
            text = { Text(stringResource(wtf.iskra.core.R.string.live_location_denied)) },
        )
    }
}

private const val BAR = 0.92f
