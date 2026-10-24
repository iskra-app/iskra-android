package wtf.iskra.features.room

import android.text.format.DateUtils
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import wtf.iskra.design.LocalAccent
import wtf.iskra.design.Theme
import wtf.iskra.features.R

/**
 * Камера над беседой, а не вместо неё: кружок — реплика в разговоре, который идёт, и тот остаётся
 * позади, под затемнением. Порт `VideoNoteComposer`.
 *
 * Палец, открывший оверлей, всё ещё лежит на кнопке композера: жест принадлежит ей, а не этому окну, —
 * поэтому рекордер живёт у экрана, а оверлей только рисует. Пока палец держит, виден [RecordLock] над
 * «затвором», стоящим там, где только что была кнопка; поймали замок — затвор становится «отправить»,
 * а рядом появляется «Отменить».
 */
@Composable
internal fun VideoNoteOverlay(
    recorder: VideoNoteRecorder,
    locked: Boolean,
    lockReach: Float,
    justLocked: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val elapsed by recorder.elapsed.collectAsStateWithLifecycle()
    val unavailable by recorder.unavailable.collectAsStateWithLifecycle()
    // Камеры нет — сказать словами и уйти: пустой чёрный круг был бы приложением, изображающим занятость.
    LaunchedEffect(unavailable) { if (unavailable) onCancel() }
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM))) {
            Column(
                Modifier.align(Alignment.Center).statusBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Theme.Spacing.roomy),
            ) {
                Camera(recorder)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Theme.Spacing.close)) {
                    val started = elapsed
                    if (started != null && started > 0) {
                        Box(Modifier.size(Theme.Size.recordingDot).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    }
                    Text(
                        DateUtils.formatElapsedTime((elapsed ?: 0L) / MILLIS),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    IconButton(onClick = recorder::flip) {
                        Icon(
                            Icons.Filled.FlipCameraAndroid,
                            contentDescription = stringResource(R.string.video_note_flip_camera),
                            tint = Color.White,
                        )
                    }
                }
                if (locked) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.recording_cancel), color = Color.White)
                    }
                } else {
                    Text(
                        stringResource(R.string.video_note_hold_to_record),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Theme.Spacing.generous),
                    )
                }
            }
            // Затвор — там, где была кнопка композера, чтобы палец, идущий к замку, не узнал, что его
            // передали: он поднимается к той же точке стекла.
            Box(
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = Theme.Spacing.close, bottom = Theme.Spacing.tight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (locked) {
                    FilledIconButton(onClick = onSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.recording_send))
                    }
                } else {
                    Box(Modifier.size(Theme.Size.minimumTapTarget).clip(CircleShape).background(LocalAccent.current.control))
                }
                if (lockReach >= 0f || justLocked) {
                    RecordLock(
                        reach = lockReach,
                        locked = justLocked,
                        modifier = Modifier.offset(y = -(Theme.Size.minimumTapTarget + Theme.Spacing.close)),
                    )
                }
            }
        }
    }
}

/**
 * Превью в круге — того самого, что уйдёт: `FILL_CENTER` показывает ту же обрезку по центру, которую
 * возьмёт `prepareNote`. Иначе человек ставил бы лицо в круг и получал бы другое.
 */
@Composable
private fun Camera(recorder: VideoNoteRecorder) {
    val owner = LocalLifecycleOwner.current
    BoxWithConstraints(Modifier.padding(horizontal = Theme.Spacing.roomy)) {
        val side = min(Theme.Size.videoNoteCamera, maxWidth)
        var preview by remember { mutableStateOf<PreviewView?>(null) }
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    preview = this
                }
            },
            modifier = Modifier.size(side).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        LaunchedEffect(recorder, owner, preview) {
            val surface = preview ?: return@LaunchedEffect
            try {
                recorder.bind(owner, surface)
                kotlinx.coroutines.awaitCancellation()
            } finally {
                recorder.unbind()
            }
        }
    }
}

private const val SCRIM = 0.72f
private const val MILLIS = 1000L
