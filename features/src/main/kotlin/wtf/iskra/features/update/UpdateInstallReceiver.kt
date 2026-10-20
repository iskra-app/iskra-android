package wtf.iskra.features.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import wtf.iskra.core.support.Log
import wtf.iskra.features.AppGraphOwner

/**
 * Ответ `PackageInstaller` на установку обновления. Просит подтверждения — показать его; отказал —
 * сказать листу обновления и открыть сеанс снова. Успех сюда обычно не доходит: процесс заменяют.
 * Объявлен в манифесте `:app`, не экспортирован.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.info(Log.Category.UPDATE, "the update was installed")
            else -> {
                Log.error(Log.Category.UPDATE, "the system did not install the update (status $status)")
                (context.applicationContext as AppGraphOwner).graph.updates.installFailed()
            }
        }
    }
}
