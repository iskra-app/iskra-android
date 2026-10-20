package wtf.iskra.core.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Куда сейчас едут пуши этого телефона — одно на устройство, на все аккаунты: токен FCM или endpoint
 * UnifiedPush выдаются приложению, а не аккаунту. Аккаунты различает `default_payload` ([PushRegistration]).
 *
 * Помнится между запусками ради выхода: снять pusher (`deletePusher`) можно, только зная, *какой*, а
 * транспорт второй раз его не скажет. Не секрет — в `SharedPreferences`, как настройки.
 */
class PushTargets(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val mutableCurrent = MutableStateFlow(read())

    /** Null — транспорт ещё не выдал адреса (или пуши на этом телефоне недоступны). */
    val current: StateFlow<PushTarget?> = mutableCurrent.asStateFlow()

    fun set(target: PushTarget?) {
        val edit = preferences.edit()
        if (target == null) edit.remove(PUSHKEY).remove(GATEWAY) else edit.putString(PUSHKEY, target.pushkey).putString(GATEWAY, target.gateway)
        edit.apply()
        mutableCurrent.value = target
    }

    private fun read(): PushTarget? {
        val pushkey = preferences.getString(PUSHKEY, null) ?: return null
        val gateway = preferences.getString(GATEWAY, null) ?: return null
        return PushTarget(pushkey, gateway)
    }

    private companion object {
        const val FILE = "iskra.push"
        const val PUSHKEY = "target.pushkey"
        const val GATEWAY = "target.gateway"
    }
}
