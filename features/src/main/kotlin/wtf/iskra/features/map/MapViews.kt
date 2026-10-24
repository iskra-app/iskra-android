package wtf.iskra.features.map

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import wtf.iskra.core.support.Log
import kotlin.coroutines.resume

private fun ensureInitialised(context: Context) {
    // Один раз за процесс; без ключа — тайлы OpenFreeMap его не просят.
    MapLibre.getInstance(context.applicationContext)
}

/**
 * Снимок места для пузыря в ленте (`MapSnapshotter`): статичная картинка без жестов и без живого GL на каждое сообщение. Рисуется
 * только когда экран его позвал — а звать его вправе лишь автозагрузка и нажатие «Показать карту».
 */
@Composable
internal fun MapSnapshot(latitude: Double, longitude: Double, width: Dp, height: Dp, description: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val dark = isSystemInDarkTheme()
    val pixelsWide = with(density) { width.roundToPx() }
    val pixelsHigh = with(density) { height.roundToPx() }
    var bitmap by remember(latitude, longitude, dark, pixelsWide, pixelsHigh) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(latitude, longitude, dark, pixelsWide, pixelsHigh) {
        if (bitmap != null) return@LaunchedEffect
        ensureInitialised(context)
        bitmap = suspendCancellableCoroutine { continuation ->
            val options = MapSnapshotter.Options(pixelsWide, pixelsHigh)
                .withStyleBuilder(Style.Builder().fromUri(MapStyle.forTheme(dark)))
                .withCameraPosition(CameraPosition.Builder().target(LatLng(latitude, longitude)).zoom(MapStyle.BUBBLE_ZOOM).build())
                .withPixelRatio(1f)
                .withLogo(false)
            val snapshotter = MapSnapshotter(context, options)
            continuation.invokeOnCancellation { runCatching { snapshotter.cancel() } }
            snapshotter.start(
                { snapshot -> if (continuation.isActive) continuation.resume(snapshot.bitmap) },
                { error ->
                    Log.error(Log.Category.MEDIA, "снимок карты не вышел: ${error.take(MAX_ERROR)}")
                    if (continuation.isActive) continuation.resume(null)
                },
            )
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** Куда летит карта выбора: новая точка и зачем (`ME_ZOOM`). */
internal class MapTarget(val latitude: Double, val longitude: Double)

/**
 * Живая карта выбора места: двигается пальцем, под перекрестием (рисует экран) — то, что уйдёт. Когда карта остановилась, сообщает центр.
 * [flyTo] — куда лететь, когда человек нажал «Моё местоположение».
 */
@Composable
internal fun PickerMap(
    start: MapTarget?,
    flyTo: MapTarget?,
    onCentre: (latitude: Double, longitude: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val centred by rememberUpdatedState(onCentre)
    val view = remember { ensureInitialised(context); MapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> Unit
                Lifecycle.Event.ON_PAUSE -> Unit
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        view.onCreate(null)
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        onDispose {
            lifecycle.removeObserver(observer)
            view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(view, dark) {
        view.getMapAsync { loaded ->
            map = loaded
            loaded.uiSettings.isRotateGesturesEnabled = false
            loaded.uiSettings.isTiltGesturesEnabled = false
            loaded.setStyle(Style.Builder().fromUri(MapStyle.forTheme(dark)))
            if (start != null) {
                loaded.cameraPosition = CameraPosition.Builder().target(LatLng(start.latitude, start.longitude)).zoom(MapStyle.ME_ZOOM).build()
            } else {
                loaded.cameraPosition = CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(MapStyle.WORLD_ZOOM).build()
            }
            loaded.addOnCameraIdleListener {
                loaded.cameraPosition.target?.let { centred(it.latitude, it.longitude) }
            }
        }
    }
    LaunchedEffect(map, flyTo) {
        val target = flyTo ?: return@LaunchedEffect
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(target.latitude, target.longitude), MapStyle.ME_ZOOM))
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private const val MAX_ERROR = 80
