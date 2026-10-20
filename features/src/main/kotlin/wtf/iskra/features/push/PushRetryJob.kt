package wtf.iskra.features.push

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import wtf.iskra.core.notifications.PushDecoder
import wtf.iskra.core.notifications.PushPayload
import wtf.iskra.core.support.Log
import wtf.iskra.features.AppGraphOwner

/**
 * Второй заход на пуш, который не расшифровался, пока транспорт ждал (docs/PROMPT.md, 10.3): на экране
 * уже заглушка, а здесь — полный бюджет, и настоящее уведомление тихо встаёт на её место.
 *
 * Платформенный `JobScheduler`, не WorkManager: тот тянет артефакт Google (`listenablefuture`), а
 * `foss` собирается без единой библиотеки Google. Expedited — с Android 12: такая задача стартует сразу
 * и в фоне; раньше — обычная без условий, которая тоже не ждёт долго.
 *
 * Объявлена в манифесте `:app` с `BIND_JOB_SERVICE`.
 */
class PushRetryJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = HashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val extras = params.extras
        val payload = PushPayload(
            roomId = extras.getString(ROOM),
            eventId = extras.getString(EVENT),
            accountId = extras.getString(ACCOUNT),
            unread = extras.getInt(UNREAD, NO_UNREAD).takeIf { it != NO_UNREAD },
        )
        val push = (applicationContext as AppGraphOwner).graph.push
        val job = scope.launch {
            try {
                push.handle(payload, PushDecoder.BUDGET, isRetry = true)
            } finally {
                synchronized(running) { running.remove(params.jobId) }
                jobFinished(params, false)
            }
        }
        synchronized(running) { running[params.jobId] = job }
        return true
    }

    /** Система забрала время. Заглушка остаётся на экране — это и было обещание на такой случай. */
    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(running) { running.remove(params.jobId) }?.cancel()
        return false
    }

    companion object {
        private const val ROOM = "room"
        private const val EVENT = "event"
        private const val ACCOUNT = "account"
        private const val UNREAD = "unread"
        private const val NO_UNREAD = -1

        fun schedule(context: Context, payload: PushPayload) {
            val extras = PersistableBundle().apply {
                putString(ROOM, payload.roomId)
                putString(EVENT, payload.eventId)
                putString(ACCOUNT, payload.accountId)
                putInt(UNREAD, payload.unread ?: NO_UNREAD)
            }
            // Одна задача на событие: второй пуш о том же не ставит вторую.
            val id = "${payload.roomId}/${payload.eventId}".hashCode()
            val builder = JobInfo.Builder(id, ComponentName(context, PushRetryJob::class.java))
                .setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(true)
            val scheduled = runCatching {
                context.getSystemService(JobScheduler::class.java).schedule(builder.build())
            }.getOrDefault(JobScheduler.RESULT_FAILURE)
            if (scheduled != JobScheduler.RESULT_SUCCESS) Log.error(Log.Category.PUSH, "the push retry was not scheduled")
        }
    }
}
