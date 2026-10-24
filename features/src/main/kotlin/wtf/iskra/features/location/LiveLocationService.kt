package wtf.iskra.features.location

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.iskra.core.support.Log
import wtf.iskra.features.R
import wtf.iskra.features.media.MediaSendService
import wtf.iskra.features.push.NotificationActionReceiver
import wtf.iskra.features.push.PushIntents
import wtf.iskra.core.R as CoreR

/** Что рисует уведомление об одном аккаунте. */
data class LiveLocationShare(
    val accountId: String,
    /** Чаты, куда идёт трансляция, — тот, что кончается позже, первым. */
    val rooms: List<Room>,
    /** Когда кончается последняя. */
    val until: java.time.Instant,
    /** Ушла ли хоть одна точка: пока нет — «ищем вас». */
    val hasPoint: Boolean,
    /** Остановку не расслышал сервер. */
    val stopFailed: Boolean,
) {
    data class Room(val id: String, val name: String)
}

/**
 * Что живая геопозиция знает сейчас — от сеанса к уведомлению, как [wtf.iskra.features.media.MediaSendHub] у отправки
 * медиа. Сервис живёт своей жизнью: должен дожить, когда экрана и, может быть, `Activity` уже нет.
 */
internal object LiveLocationHub {
    private val mutable = MutableStateFlow<Map<String, LiveLocationShare>>(emptyMap())
    val shares: StateFlow<Map<String, LiveLocationShare>> = mutable.asStateFlow()

    /** Значок в строке состояния — знак Искры (его отдаёт `AppGraph`). */
    @Volatile
    var smallIcon: Int = 0

    /** «Остановить» из уведомления → все трансляции сеансов. Ставит `RootModel`, пока сеанс жив. */
    @Volatile
    var stopAll: (() -> Unit)? = null

    fun update(accountId: String, share: LiveLocationShare?) {
        mutable.value = if (share == null) mutable.value - accountId else mutable.value + (accountId to share)
    }
}

/**
 * Держит процесс живым, пока положение уходит в чаты, и показывает это в шторке: foreground-сервис типа `location` с
 * кнопкой «Остановить». Аналог Live Activity и синей таблетки на iOS (`LiveLocationActivity`): человек всегда видит, что его
 * положение покидает телефон. Сам положение не читает — это делает `LiveLocationSharer` сеанса, пока сервис не даёт процессу
 * умереть.
 *
 * Запускается из видимого приложения (человек только что нажал «Делиться»): так Android разрешает foreground-сервис
 * с доступом к положению. Трансляцию, которую подняли после смерти процесса, он не запустит — она идёт без уведомления,
 * пока приложение не откроют (то же и в iOS: «без Live Activity, пока приложение не откроют»).
 */
class LiveLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaSendService.createChannel(this)
        startForegroundCompat(notification(this, LiveLocationHub.shares.value.values.firstOrNull()))
        if (watching == null) {
            watching = scope.launch { LiveLocationHub.shares.collect { render(it) } }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun render(shares: Map<String, LiveLocationShare>) {
        val share = shares.values.firstOrNull()
        if (share == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching { NotificationManagerCompat.from(this).notify(ID, notification(this, share)) }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(ID, notification)
            }
        } catch (error: Exception) {
            Log.error(Log.Category.ROOMS, "сервис геопозиции не встал на передний план: ${Log.describe(error)}")
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val ID = 71_002

        /** Из видимого приложения — иначе Android откажет. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LiveLocationService::class.java))
            } catch (error: Exception) {
                Log.error(Log.Category.ROOMS, "сервис геопозиции не запустился: ${Log.describe(error)}")
            }
        }

        internal fun notification(context: Context, share: LiveLocationShare?): Notification {
            val builder = NotificationCompat.Builder(context, MediaSendService.CHANNEL)
                .setSmallIcon(LiveLocationHub.smallIcon)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentTitle(context.getString(CoreR.string.live_activity_location_sharing))
            if (share == null) return builder.build()
            builder.setContentText(
                when {
                    share.stopFailed -> context.getString(CoreR.string.live_activity_location_stop_failed)
                    !share.hasPoint -> context.getString(CoreR.string.live_activity_location_finding)
                    else -> share.rooms.joinToString { it.name }
                },
            )
            builder.setWhen(share.until.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            share.rooms.firstOrNull()?.let { open.putExtra(PushIntents.EXTRA_ROOM, it.id).putExtra(PushIntents.EXTRA_ACCOUNT, share.accountId) }
            builder.setContentIntent(PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            val stop = Intent(context, NotificationActionReceiver::class.java).setAction(PushIntents.ACTION_STOP_LIVE_LOCATION)
            builder.addAction(
                0,
                context.getString(CoreR.string.live_activity_location_stop),
                PendingIntent.getBroadcast(context, ID, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            return builder.build()
        }
    }
}
