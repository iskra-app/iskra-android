package wtf.iskra.features.media

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import wtf.iskra.core.support.Log
import wtf.iskra.features.update.UpdateService

/**
 * Как пустить в фон то, что человек только что начал и ждёт: загрузку видео и скачивание обновления (docs/PROMPT.md, 11.5 и 19.1).
 *
 * **Android 14+ — user-initiated data transfer job** ([TransferJobService]): так система и предлагает делать то, что начал сам человек и что идёт долго, —
 * задача с уведомлением, которой не мешают ограничения фоновых сервисов и которую человек видит в «Диспетчере задач». Запускается, пока приложение на
 * экране, то есть сразу после нажатия «отправить» или «Установить».
 *
 * **Ниже и когда систему задача не устроила** — foreground-сервис типа `dataSync`: тот же результат (процесс жив, уведомление идёт), другой механизм.
 */
internal object BackgroundTransfer {
    enum class Kind(val jobId: Int) {
        /** Отправка видео, альбома, большого файла ([MediaSendHub]). */
        MEDIA(71_001),

        /** Скачивание обновления ([wtf.iskra.features.update.Updates]). */
        UPDATE(71_002),
    }

    internal const val EXTRA_KIND = "kind"

    fun start(context: Context, kind: Kind) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && schedule(context, kind)) return
        when (kind) {
            Kind.MEDIA -> MediaSendService.startService(context)
            Kind.UPDATE -> UpdateService.startService(context)
        }
    }

    /**
     * Поставить задачу; true — система её приняла (или она уже стоит) и сервис не нужен. Разрешение `RUN_USER_INITIATED_JOBS` объявлено в манифесте
     * `:app` (lint `:features` его не видит), а запуск — только из видимого приложения.
     */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun schedule(context: Context, kind: Kind): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        // Уже стоит — тот же ход читает она: заново не ставим, а то система перезапустит её посреди дела.
        if (scheduler.getPendingJob(kind.jobId) != null) return true
        val info = JobInfo.Builder(kind.jobId, ComponentName(context, TransferJobService::class.java))
            .setUserInitiated(true)
            // Сеть нужна всегда: без неё задаче нечего делать, а UIDT без требования к сети не принимается.
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setExtras(PersistableBundle().apply { putInt(EXTRA_KIND, kind.ordinal) })
            .build()
        return try {
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        } catch (error: Exception) {
            Log.error(Log.Category.MEDIA, "задачу передачи не приняли: ${Log.describe(error)}")
            false
        }
    }
}
