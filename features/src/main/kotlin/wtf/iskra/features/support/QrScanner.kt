package wtf.iskra.features.support

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.rooms.MatrixLink
import wtf.iskra.core.support.Log
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Чтение чужого QR-кода: камера или картинка с кодом из галереи. Порт `QRScannerView`.
 *
 * Берутся только ссылки Matrix — человек или комната в любом написании, что читает [MatrixLink.parse]. Всё прочее, что
 * увидит камера (меню, пароль от Wi-Fi), называется «не оно», и камера смотрит дальше: навести телефон не на тот код —
 * не ошибка, ради которой нужен диалог.
 */
@Composable
fun QrScanner(onFound: (MatrixLink) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var camera by remember {
        mutableStateOf(if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) true else null)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    LaunchedEffect(Unit) { if (camera == null) permission.launch(Manifest.permission.CAMERA) }
    // Фраза вместо подсказки, на пару секунд — и подсказка снова: камера всё ещё смотрит.
    var note by remember { mutableStateOf<Int?>(null) }
    var noteEpoch by remember { mutableStateOf(0) }
    val found by rememberUpdatedState(onFound)

    fun read(texts: List<String>) {
        val link = texts.firstNotNullOfOrNull(MatrixLink::parse)
        if (link != null) {
            found(link)
        } else {
            note = R.string.qr_not_matrix
            noteEpoch += 1
        }
    }
    LaunchedEffect(noteEpoch) {
        if (note != null) {
            delay(NOTE_MS)
            note = null
        }
    }
    val picture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val code = withContext(Dispatchers.IO) { decodePicture(context, uri) }
            if (code == null) {
                note = R.string.qr_nothing_found
                noteEpoch += 1
            } else {
                read(listOf(code))
            }
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(horizontal = Theme.Spacing.roomy)
                .padding(top = Theme.Spacing.roomy)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Theme.Radius.card))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when (camera) {
                true -> CameraPreview(onCode = { read(listOf(it)) })
                false -> Text(
                    stringResource(R.string.qr_no_camera),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(Theme.Spacing.generous),
                )
                null -> CircularProgressIndicator(color = LocalAccent.current.control)
            }
        }
        Text(
            stringResource(note ?: R.string.qr_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = if (note == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Theme.Spacing.generous),
        )
        // Системный выбор работает вне приложения и отдаёт только выбранное, поэтому доступа к галерее ему не нужно.
        TextButton(onClick = { picture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.padding(end = Theme.Spacing.close))
            Text(stringResource(R.string.qr_choose_picture))
        }
    }
}

/** Камера с превью; каждый кадр идёт в [QrCodeReader] на своём потоке, найденное — на главном. Кадры никуда не сохраняются. */
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val current by rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val view = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        val alive = AtomicBoolean(true)
        future.addListener({
            if (!alive.get()) return@addListener
            try {
                val bound = future.get().also { provider = it }
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { use ->
                        use.setAnalyzer(executor) { image ->
                            try {
                                val plane = image.planes[0]
                                val buffer = plane.buffer
                                val luma = ByteArray(buffer.remaining()).also(buffer::get)
                                QrCodeReader.read(luma, plane.rowStride, image.width, image.height)?.let { text ->
                                    ContextCompat.getMainExecutor(context).execute { if (alive.get()) current(text) }
                                }
                            } catch (error: Exception) {
                                Log.error(Log.Category.MEDIA, "кадр не прочитался: ${Log.describe(error)}")
                            } finally {
                                image.close()
                            }
                        }
                    }
                bound.unbindAll()
                bound.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (error: Exception) {
                Log.error(Log.Category.MEDIA, "камера не поднялась: ${Log.describe(error)}")
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            alive.set(false)
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** Картинка, уменьшенная до разумного, — и код на ней. Больше [MAX_SIDE] точек QR не лучше читается, а память стоит. */
private fun decodePicture(context: android.content.Context, uri: Uri): String? = try {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > MAX_SIDE) decoder.setTargetSampleSize((longest + MAX_SIDE - 1) / MAX_SIDE)
    }
    try {
        QrCodeReader.read(bitmap)
    } finally {
        bitmap.recycle()
    }
} catch (error: Exception) {
    Log.error(Log.Category.MEDIA, "картинка с кодом не открылась: ${Log.describe(error)}")
    null
}

private const val NOTE_MS = 3000L
private const val MAX_SIDE = 2048
