package wtf.iskra

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import wtf.iskra.core.notifications.PushPayload

/**
 * Пуши FCM. Только проводка: пуш уходит в `Push.handle` (`:features`), который не знает, каким
 * транспортом тот приехал. `onMessageReceived` зовётся не на главном потоке и даёт около десяти
 * секунд: `Push` укладывается в восемь, а не успел — заглушка и повтор фоновой задачей.
 */
class IskraMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FlavorPush.tokenArrived(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val push = (application as IskraApplication).graph.push
        runBlocking { push.handle(PushPayload.fromFcm(message.data)) }
    }
}
