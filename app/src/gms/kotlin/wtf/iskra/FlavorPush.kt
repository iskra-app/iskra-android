package wtf.iskra

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import wtf.iskra.core.notifications.PushRegistration
import wtf.iskra.core.notifications.PushTarget
import wtf.iskra.core.support.Log
import wtf.iskra.features.push.PushTransport

/**
 * Пуши `gms` — FCM (docs/PROMPT.md, 10.2). Пуш идёт сервер → наш Sygnal (`push.iskra.wtf`, вход
 * `wtf.iskra-android`) → Google → [IskraMessagingService]. Сообщение data-only и high priority: так
 * Android будит сервис и у убитого приложения, и в Doze.
 *
 * Работает, только когда сборка знает проект Firebase (`app/src/gms/google-services.json`); без него
 * Firebase не поднимается, и транспорт честно говорит [PushTransport.State.UNAVAILABLE].
 */
object FlavorPush : PushTransport {
    override fun start(context: Context) {
        if (!configured(context)) {
            Log.error(Log.Category.PUSH, "this build has no Firebase project, so FCM cannot deliver pushes")
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> tokenArrived(context, token) }
            .addOnFailureListener { error -> Log.error(Log.Category.PUSH, "FCM gave no token: ${Log.describe(error)}") }
    }

    override fun stop(context: Context) {
        if (!configured(context)) return
        FirebaseMessaging.getInstance().deleteToken()
    }

    override fun state(context: Context): PushTransport.State = when {
        !configured(context) -> PushTransport.State.UNAVAILABLE
        (context.applicationContext as IskraApplication).graph.pushTargets.current.value != null -> PushTransport.State.READY
        else -> PushTransport.State.WAITING
    }

    /** Новый токен — новый адрес; вошедший сеанс отнесёт его серверу (`RootModel`). */
    internal fun tokenArrived(context: Context, token: String) {
        (context.applicationContext as IskraApplication).graph.push.targetChanged(PushTarget(token, PushRegistration.GATEWAY))
    }

    /** Firebase поднимается сам при запуске — если сборка знает проект. Иначе приложений Firebase нет. */
    private fun configured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()
}
