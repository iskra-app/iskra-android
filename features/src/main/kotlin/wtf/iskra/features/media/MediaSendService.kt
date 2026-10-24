package wtf.iskra.features.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
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
import wtf.iskra.core.media.MediaSendJobs
import wtf.iskra.core.support.Log
import wtf.iskra.features.R
import wtf.iskra.features.push.PushIntents
import wtf.iskra.core.R as CoreR

/**
 * Что отправка медиа знает сейчас — от сеанса к уведомлению. Один на процесс: сеанс кладёт сюда задачи
 * (`RootModel` следит за `UserSession.mediaJobs`), а [MediaSendService] их читает и рисует. Между ними — не прямой
 * вызов, потому что у сервиса своя жизнь: он обязан дожить до конца загрузки, когда экрана и, может быть, `Activity` уже нет.
 */
internal object MediaSendHub {
    data class State(val accountId: String? = null, val jobs: List<MediaSendJobs.Job> = emptyList())

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    /** Значок в строке состояния — знак Искры, живущий в `:app` (его отдаёт `AppGraph`). */
    @Volatile
    var smallIcon: Int = 0

    fun update(accountId: String, jobs: List<MediaSendJobs.Job>) {
        mutable.value = State(accountId, jobs)
    }

    fun clear() {
        mutable.value = State()
    }
}

/**
 * Держит процесс живым, пока видео, альбом или большой файл уходят, и показывает, как идёт: foreground-сервис типа
 * `dataSync` с уведомлением о прогрессе. Аналог Live Activity и индикатора фоновой задачи на iOS (`MediaSendJobs`).
 *
 * Без него система убивала бы приложение, едва человек ушёл на другой экран, — и загрузка (очередь SDK переживёт
 * убийство, но пойдёт дальше только при следующем запуске) стояла бы, пока он не вернётся. Тап по уведомлению открывает чат;
 * то, что не отправилось насовсем, остаётся отдельным уведомлением и после того, как сервис ушёл.
 *
 * Запускается из видимого приложения (человек только что нажал «отправить»): так Android разрешает foreground-сервис.
 * Пока это один сервис типа `dataSync` для всех версий; user-initiated data transfer job (Android 14+, docs/PROMPT.md,
 * 11.5) — отдельным пунктом.
 */
class MediaSendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching: Job? = null
    private val failedNotified = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        // До пяти секунд на `startForeground`: уведомление строится из того, что есть сейчас, а дальше обновляется.
        val first = MediaSendHub.state.value
        val shown = first.jobs.firstOrNull { !it.isFinished } ?: first.jobs.firstOrNull()
        startForegroundCompat(notification(this, first.accountId, shown))
        if (watching == null) {
            watching = scope.launch {
                MediaSendHub.state.collect { state -> render(state) }
            }
        }
        return START_NOT_STICKY
    }

    // Без разрешения на уведомления `notify` молча ничего не делает (или бросает) — загрузка от этого не страдает.
    @SuppressLint("MissingPermission")
    private fun render(state: MediaSendHub.State) {
        val manager = NotificationManagerCompat.from(this)
        postFailures(this, state, failedNotified)
        val active = state.jobs.firstOrNull { !it.isFinished }
        if (active == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        runCatching { manager.notify(ID, notification(this, state.accountId, active)) }
    }

    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, notification)
            }
        } catch (error: Exception) {
            Log.error(Log.Category.MEDIA, "сервис отправки не встал на передний план: ${Log.describe(error)}")
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
        internal const val ID = 71_001
        private const val FAILED_BASE = 71_100
        const val CHANNEL = "background_tasks"

        private fun failedId(jobId: String) = FAILED_BASE + (jobId.hashCode() and 0xFFF)

        /** Не отправилось насовсем — отдельное уведомление, что переживёт и сервис, и задачу. Общее для сервиса и [TransferJobService]. */
        @SuppressLint("MissingPermission")
        internal fun postFailures(context: Context, state: MediaSendHub.State, notified: MutableSet<String>) {
            val manager = NotificationManagerCompat.from(context)
            state.jobs.filter { it.phase == MediaSendJobs.Phase.FAILED && notified.add(it.id) }.forEach { job ->
                runCatching { manager.notify(failedId(job.id), notification(context, state.accountId, job)) }
            }
        }

        /** Запустить (или оставить запущенным): задачи уже в [MediaSendHub]. Из видимого приложения — иначе Android откажет. */
        fun start(context: Context) = BackgroundTransfer.start(context, BackgroundTransfer.Kind.MEDIA)

        /** Сервис переднего плана — путь до Android 14 и откат, когда задачу система не приняла. */
        internal fun startService(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MediaSendService::class.java))
            } catch (error: Exception) {
                // Приложение не на переднем плане (редко: задача родилась в фоне) — без уведомления, но очередь SDK идёт.
                Log.error(Log.Category.MEDIA, "сервис отправки не запустился: ${Log.describe(error)}")
            }
        }

        /** Канал «Фоновые задачи»: отправка медиа и скачивание обновления делят его — оба тихие и идут, пока кто-то ждёт. */
        internal fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.notification_channel_background))
                .setShowBadge(false)
                .build()
            runCatching { NotificationManagerCompat.from(context).createNotificationChannel(channel) }
        }

        internal fun notification(context: Context, accountId: String?, job: MediaSendJobs.Job?): Notification {
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(MediaSendHub.smallIcon)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOnlyAlertOnce(true)
                .setSilent(true)
            if (job == null) return builder.setContentTitle(context.getString(R.string.settings_app_name)).build()
            builder.setContentTitle(title(context, job))
            builder.setContentText(job.roomName.ifEmpty { context.getString(R.string.settings_app_name) })
            // Нажатие — в этот чат, в его аккаунт (`PushRouter`).
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            open.putExtra(PushIntents.EXTRA_ROOM, job.roomId)
            if (accountId != null) open.putExtra(PushIntents.EXTRA_ACCOUNT, accountId)
            builder.setContentIntent(
                PendingIntent.getActivity(context, job.id.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            when (job.phase) {
                MediaSendJobs.Phase.PREPARING -> builder
                    .setOngoing(true)
                    .setProgress(PERCENT, (job.progress * PERCENT).toInt(), job.progress <= 0.0)
                    .setSubText(context.getString(CoreR.string.live_activity_media_preparing))
                MediaSendJobs.Phase.UPLOADING -> builder.setOngoing(true).setProgress(PERCENT, (job.progress * PERCENT).toInt(), false)
                MediaSendJobs.Phase.SENT -> builder.setOngoing(false).setSubText(context.getString(CoreR.string.live_activity_media_sent))
                MediaSendJobs.Phase.FAILED -> builder
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setContentText(context.getString(CoreR.string.live_activity_media_failed))
                    .setSubText(job.roomName)
            }
            return builder.build()
        }

        private fun title(context: Context, job: MediaSendJobs.Job): String = context.getString(
            when (val what = job.what) {
                MediaSendJobs.What.Video -> CoreR.string.live_activity_media_sending_video
                MediaSendJobs.What.File -> CoreR.string.live_activity_media_sending_file
                is MediaSendJobs.What.Photos -> if (what.count == 1) CoreR.string.live_activity_media_sending_photo else CoreR.string.live_activity_media_sending_album
                is MediaSendJobs.What.Media -> CoreR.string.live_activity_media_sending_album
            },
        )

        private const val PERCENT = 100
    }
}
