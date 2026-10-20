package wtf.iskra

import android.content.Context
import wtf.iskra.features.push.PushTransport

/** Пуши `gms` — FCM (docs/PROMPT.md, 10.2). Подключается следующим шагом этапа 7. */
object FlavorPush : PushTransport {
    override fun start(context: Context) = Unit
    override fun stop(context: Context) = Unit
    override fun state(context: Context) = PushTransport.State.UNAVAILABLE
}
