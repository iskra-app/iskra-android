package wtf.iskra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import wtf.iskra.core.notifications.PushPayload
import wtf.iskra.core.notifications.PushTarget
import wtf.iskra.core.support.Log
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Что дистрибьютор UnifiedPush говорит приложению: адрес, сообщение, «больше не доставляю». Только
 * проводка: пуш уходит в `Push.handle` (`:features`), адрес — в `Push.targetChanged`. Сообщение и адрес
 * принимаются только с нашим токеном.
 */
class UnifiedPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(FlavorPush.EXTRA_TOKEN)
        if (!FlavorPush.isOurs(context, token)) return
        val push = (context.applicationContext as IskraApplication).graph.push
        val pending = goAsync()
        scope.launch {
            try {
                when (intent.action) {
                    ACTION_NEW_ENDPOINT -> {
                        val endpoint = intent.getStringExtra(EXTRA_ENDPOINT) ?: return@launch
                        FlavorPush.rememberEndpoint(context, endpoint)
                        push.targetChanged(PushTarget(pushkey = endpoint, gateway = gateway(endpoint)))
                    }
                    ACTION_MESSAGE -> {
                        val bytes = intent.getByteArrayExtra(EXTRA_BYTES) ?: intent.getStringExtra(EXTRA_MESSAGE)?.toByteArray()
                        FlavorPush.acknowledge(context, token!!, intent.getStringExtra(FlavorPush.EXTRA_ID))
                        val payload = bytes?.let { PushPayload.fromMatrixNotify(it.decodeToString()) } ?: return@launch
                        push.handle(payload)
                    }
                    ACTION_UNREGISTERED -> {
                        FlavorPush.rememberEndpoint(context, null)
                        push.targetChanged(null)
                    }
                    ACTION_REGISTRATION_FAILED -> Log.error(Log.Category.PUSH, "the UnifiedPush distributor refused to register us")
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Matrix-шлюз дистрибьютора: тот же хост, путь `/_matrix/push/v1/notify` (так у ntfy и у
     * большинства). Спецификация велит спросить сам шлюз, и он отвечает `{"unifiedpush":{"gateway":"matrix"}}`;
     * не ответил — адрес всё равно этот: чужой общий шлюз узнал бы наш endpoint, а это хуже, чем
     * сказать в логе, что пуши могут не дойти.
     */
    private fun gateway(endpoint: String): String {
        val uri = URI(endpoint)
        val gateway = URI(uri.scheme, null, uri.host, uri.port, MATRIX_GATEWAY_PATH, null, null).toString()
        val confirmed = runCatching {
            val connection = URL(gateway).openConnection() as HttpURLConnection
            connection.connectTimeout = DISCOVERY_TIMEOUT_MS
            connection.readTimeout = DISCOVERY_TIMEOUT_MS
            try {
                connection.inputStream.use { it.readBytes().decodeToString() }.contains("\"matrix\"")
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        if (!confirmed) Log.error(Log.Category.PUSH, "the distributor did not confirm a Matrix gateway; pushes may not arrive")
        return gateway
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
        const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
        const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
        const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_BYTES = "bytesMessage"
        const val EXTRA_MESSAGE = "message"
        const val MATRIX_GATEWAY_PATH = "/_matrix/push/v1/notify"
        const val DISCOVERY_TIMEOUT_MS = 5_000
    }
}
