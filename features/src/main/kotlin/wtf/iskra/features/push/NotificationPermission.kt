package wtf.iskra.features.push

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.AppSettings

/**
 * Разрешение на уведомления (Android 13+) — **после входа**, а не на пустом экране входа, где вопрос
 * ни о чём и «нет» становится навсегда (docs/PROMPT.md, 10.5; `PushPermission.swift`). Один раз: дальше
 * — экран «Уведомления», который говорит, что система не пропускает, и ведёт в её настройки.
 * Отказ — молча: пуши всё равно регистрируются, и включить их можно позже.
 */
@Composable
internal fun NotificationPermission(session: UserSession, settings: AppSettings?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || session.isDemo || settings == null) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(session.sessionId) {
        if (settings.askedForNotifications) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        settings.setAskedForNotifications()
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
