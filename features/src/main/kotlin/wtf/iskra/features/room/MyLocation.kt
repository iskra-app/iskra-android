package wtf.iskra.features.room

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import wtf.iskra.features.location.hasLocationPermission
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import wtf.iskra.core.R as CoreR

/**
 * «Место → Моё местоположение»: где человек сейчас — точкой, которую он видит, прежде чем отправить.
 * `LocationManager`, а не Fused из сервисов Google: работает и во `foss` (docs/PROMPT.md, 11.6). Выбор
 * на карте (MapLibre) — следующим пунктом.
 */
@Composable
internal fun MyLocationDialog(
    onSend: (latitude: Double, longitude: Double) -> Unit,
    onDismiss: () -> Unit,
    /** Делиться, пока идёшь, и сколько. Null — комната не умеет (тред, демо, нет датчика). */
    onShareLive: ((Duration) -> Unit)? = null,
) {
    var choosingDuration by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(hasLocationPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    var point by remember { mutableStateOf<Location?>(null) }
    var failed by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        allowed = granted.values.any { it }
        denied = !allowed
    }
    LaunchedEffect(Unit) {
        if (!allowed) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        point = currentLocation(context)
        failed = point == null
    }
    val found = point
    if (choosingDuration && onShareLive != null) {
        LiveDurationDialog(onPick = onShareLive, onDismiss = { choosingDuration = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_my_location)) },
        text = {
            when {
                denied -> Text(stringResource(CoreR.string.live_location_denied))
                found != null -> Text(stringResource(R.string.location_coordinates, found.latitude, found.longitude))
                failed -> Text(stringResource(CoreR.string.live_location_start_failed))
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
                    CircularProgressIndicator(color = LocalAccent.current.control, modifier = Modifier.size(Theme.Size.menuIcon))
                    Text(stringResource(CoreR.string.live_activity_location_finding))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = found != null, onClick = { found?.let { onSend(it.latitude, it.longitude) } }) {
                Text(stringResource(R.string.location_send))
            }
        },
        dismissButton = {
            Row {
                if (onShareLive != null) {
                    TextButton(enabled = allowed, onClick = { choosingDuration = true }) { Text(stringResource(R.string.location_share_live)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.location_cancel)) }
            }
        },
    )
}

/** «На сколько?» — 15 минут, час, 8 часов. Те же три, что в iOS. */
@Composable
private fun LiveDurationDialog(onPick: (Duration) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        pluralStringResource(R.plurals.location_live_for_minutes, 15, 15) to 15.minutes,
        pluralStringResource(R.plurals.location_live_for_hours, 1, 1) to 1.hours,
        pluralStringResource(R.plurals.location_live_for_hours, 8, 8) to 8.hours,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_share_live_for)) },
        text = {
            Column {
                options.forEach { (label, duration) ->
                    TextButton(onClick = { onPick(duration) }) { Text(label) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.location_cancel)) } },
    )
}

/**
 * Одна свежая точка: спутники, если включены, иначе сеть. Ограничено: в помещении спутники могут не
 * ответить никогда, и диалог не должен крутиться вечно.
 */
@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? = runCatching {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
    withTimeLimit(LOCATION_LIMIT) {
        suspendCancellableCoroutine { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    }
}.onFailure { Log.error(Log.Category.MEDIA, "место не нашлось: ${Log.describe(it)}") }.getOrNull()

private val LOCATION_LIMIT = 30.seconds
