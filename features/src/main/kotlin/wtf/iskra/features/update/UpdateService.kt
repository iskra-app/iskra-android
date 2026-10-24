package wtf.iskra.features.update

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import wtf.iskra.core.support.Log
import wtf.iskra.features.R
import wtf.iskra.features.media.MediaSendHub
import wtf.iskra.features.media.MediaSendService

/**
 * Держит процесс живым, пока обновление скачивается и проверяется, когда человек ушёл из приложения: foreground-сервис типа `dataSync` с
 * уведомлением о ходе. Без него система убила бы фоновое приложение посреди скачивания, и «Установить» пришлось бы начинать заново (docs/PROMPT.md,
 * 19.1). Тап по уведомлению открывает приложение — на лист обновления, где и идёт тот же ход.
 *
 * Сервис ничего не качает сам: качает [Updates], в своей области, а это только то, что не даёт процессу умереть, и окно, где виден ход. Поэтому его
 * жизнь — это состояние установки: скачивание и проверка — стоит, всё остальное (поставилось, отказ, сеть) — уходит. Пока один сервис `dataSync` на
 * все версии; user-initiated data transfer job (Android 14+, docs/PROMPT.md, 11.5) — отдельным пунктом вместе с отправкой медиа.
 */
class UpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaSendService.createChannel(this)
        val updates = Updates.current
        startForegroundCompat(notification(this, null))
        if (updates == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watching == null) {
            watching = scope.launch {
                updates.install.collect { state ->
                    when (state) {
                        is Updates.Install.Downloading -> notify(notification(this@UpdateService, state.fraction))
                        Updates.Install.Verifying, Updates.Install.Installing -> notify(notification(this@UpdateService, null))
                        else -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun notify(notification: Notification) {
        runCatching { NotificationManagerCompat.from(this).notify(ID, notification) }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, notification)
            }
        } catch (error: Exception) {
            Log.error(Log.Category.UPDATE, "сервис обновления не встал на передний план: ${Log.describe(error)}")
            stopSelf()
        }
    }

    /** Android 15 ограничивает `dataSync` по времени: система говорит, что срок вышел, — уходим сами. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ID = 71_002
        private const val PERCENT = 100

        /** Из видимого приложения — иначе Android откажет: человек только что нажал «Установить». */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java))
            } catch (error: Exception) {
                // Не вышло — скачивание идёт, пока процесс жив, но без уведомления.
                Log.error(Log.Category.UPDATE, "сервис обновления не запустился: ${Log.describe(error)}")
            }
        }

        /** [fraction] — доля скачанного; null — идёт проверка или установка, и полосе нечего мерить. */
        private fun notification(context: Context, fraction: Float?): Notification {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            return NotificationCompat.Builder(context, MediaSendService.CHANNEL)
                .setSmallIcon(MediaSendHub.smallIcon)
                .setContentTitle(context.getString(R.string.update_downloading))
                .setContentText(context.getString(R.string.settings_app_name))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(true)
                .setProgress(PERCENT, ((fraction ?: 0f) * PERCENT).toInt(), fraction == null)
                .setContentIntent(PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .build()
        }
    }
}
