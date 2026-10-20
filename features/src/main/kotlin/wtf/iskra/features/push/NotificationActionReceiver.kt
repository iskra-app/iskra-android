package wtf.iskra.features.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import wtf.iskra.features.AppGraphOwner

/**
 * «Ответить» и «Прочитано» на уведомлении. Работает без экранов и часто без живого приложения:
 * `goAsync` держит процесс, пока ответ не уйдёт (у [wtf.iskra.core.notifications.NotificationActions]
 * свой предел, меньше системного). Объявлен в манифесте `:app`, не экспортирован.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(PushIntents.EXTRA_ROOM) ?: return
        val accountId = intent.getStringExtra(PushIntents.EXTRA_ACCOUNT) ?: return
        val push = (context.applicationContext as AppGraphOwner).graph.push
        val text = when (intent.action) {
            PushIntents.ACTION_REPLY ->
                RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PushIntents.KEY_REPLY)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return
            PushIntents.ACTION_READ -> null
            else -> return
        }
        val pending = goAsync()
        scope.launch {
            try {
                if (text != null) push.reply(roomId, accountId, text) else push.markRead(roomId, accountId)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
