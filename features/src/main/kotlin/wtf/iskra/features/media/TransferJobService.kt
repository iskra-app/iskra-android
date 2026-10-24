package wtf.iskra.features.media

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import wtf.iskra.features.update.UpdateService
import wtf.iskra.features.update.Updates

/**
 * User-initiated data transfer job (Android 14+): держит процесс живым, пока уходит видео или скачивается обновление, и показывает уведомление
 * того же вида, что и сервисы ([BackgroundTransfer]). Ничего не качает и не шлёт сам: это делают [MediaSendHub]/`MediaSendJobs` и [Updates] в
 * своих областях, а задача — то, что не даёт процессу умереть, и окно, где виден ход. Поэтому её жизнь — состояние того, за чем она следит: пока
 * идёт — стоит, потом [jobFinished].
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class TransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watching = HashMap<Int, Job>()
    private val failedNotified = HashSet<String>()

    override fun onStartJob(params: JobParameters): Boolean {
        MediaSendService.createChannel(this)
        val kind = BackgroundTransfer.Kind.entries.getOrNull(params.extras.getInt(BackgroundTransfer.EXTRA_KIND, -1)) ?: return false
        watching.remove(params.jobId)?.cancel()
        watching[params.jobId] = when (kind) {
            BackgroundTransfer.Kind.MEDIA -> scope.launch {
                MediaSendHub.state.collect { state ->
                    MediaSendService.postFailures(this@TransferJobService, state, failedNotified)
                    val active = state.jobs.firstOrNull { !it.isFinished }
                    if (active == null) finish(params) else show(params, MediaSendService.ID, MediaSendService.notification(this@TransferJobService, state.accountId, active))
                }
            }
            BackgroundTransfer.Kind.UPDATE -> scope.launch {
                val updates = Updates.current ?: return@launch finish(params)
                updates.install.collect { state ->
                    when (state) {
                        is Updates.Install.Downloading -> show(params, UpdateService.ID, UpdateService.notification(this@TransferJobService, state.fraction))
                        Updates.Install.Verifying, Updates.Install.Installing -> show(params, UpdateService.ID, UpdateService.notification(this@TransferJobService, null))
                        else -> finish(params)
                    }
                }
            }
        }
        return true
    }

    /** Уведомление задачи: то, что видно, пока она идёт. Обновляется тем же вызовом. */
    private fun show(params: JobParameters, id: Int, notification: android.app.Notification) {
        runCatching { setNotification(params, id, notification, JOB_END_NOTIFICATION_POLICY_REMOVE) }
    }

    private fun finish(params: JobParameters) {
        watching.remove(params.jobId)?.cancel()
        jobFinished(params, false)
    }

    /** Система остановила задачу (срок, нет сети, человек из «Диспетчера»): следить больше не за чем; процесс умрёт сам, когда уйдёт и всё остальное. */
    override fun onStopJob(params: JobParameters): Boolean {
        watching.remove(params.jobId)?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
