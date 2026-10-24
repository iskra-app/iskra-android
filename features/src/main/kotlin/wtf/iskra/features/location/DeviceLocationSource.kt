package wtf.iskra.features.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import wtf.iskra.core.rooms.LivePoint
import wtf.iskra.core.rooms.LocationSource
import wtf.iskra.core.support.Log
import java.time.Instant

/**
 * Положение устройства для живой геопозиции — `LocationManager`, а не Fused из сервисов Google: работает и во `foss`
 * (docs/PROMPT.md, 11.6). Слушает спутники и сеть сразу: в помещении отвечает вторая, на улице — первая, а отправку точек
 * всё равно сдерживает `LiveLocationSharer`.
 */
class DeviceLocationSource(private val context: Context) : LocationSource {
    override fun isAllowed(): Boolean = hasLocationPermission(context)

    @SuppressLint("MissingPermission")
    override fun updates(): Flow<LivePoint> = callbackFlow {
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { manager != null && runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (manager == null || providers.isEmpty() || !hasLocationPermission(context)) {
            close()
            return@callbackFlow
        }
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(LivePoint(location.latitude, location.longitude, Instant.ofEpochMilli(location.time)))
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        val request = LocationRequestCompat.Builder(INTERVAL_MS).setMinUpdateDistanceMeters(MIN_DISTANCE_METERS).build()
        val started = providers.filter { provider ->
            runCatching { LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(context), listener) }
                .onFailure { Log.error(Log.Category.ROOMS, "датчик $provider не включился: ${Log.describe(it)}") }
                .isSuccess
        }
        if (started.isEmpty()) close()
        awaitClose { runCatching { manager.removeUpdates(listener as LocationListener) } }
    }

    private companion object {
        const val INTERVAL_MS = 5_000L
        const val MIN_DISTANCE_METERS = 10f
    }
}

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
