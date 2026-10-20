package wtf.iskra.features.push

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import wtf.iskra.core.notifications.PushTarget
import wtf.iskra.core.notifications.PushTargets

/**
 * Что корень приложения (`RootModel`) говорит пушам и что от них слышит. Отдельно, чтобы модель корня
 * не знала ни про `Context`, ни про транспорт, а тесты её собирались без них.
 */
class RootPushes(
    private val context: Context,
    val push: Push,
    val router: PushRouter,
    private val targets: PushTargets,
) {
    /** Куда сейчас едут пуши телефона. Каждое новое значение вошедший сеанс регистрирует на сервере. */
    val target: StateFlow<PushTarget?> get() = targets.current

    /** На каком языке сервер напишет свою заглушку, если до расшифровки не дойдёт. */
    fun language(): String = wtf.iskra.features.appLanguage(context)

    /** Вошли (или открыли вошедшего): попросить у транспорта адрес. Повторный вызов безвреден. */
    fun signedIn() = push.transport.start(context)

    /** Аккаунт вышел. Последний — адрес транспорта больше никому не нужен. */
    fun signedOut(accountId: String, anyoneLeft: Boolean) {
        push.signedOut(accountId)
        if (!anyoneLeft) {
            push.transport.stop(context)
            targets.set(null)
        }
    }

    /** Чат на экране — или никакого ([roomId] null), или приложение ушло в фон. */
    fun visible(roomId: String?, accountId: String?) {
        router.visibleRoomId = roomId
        router.visibleAccountId = accountId
        if (roomId != null) push.roomOpened(roomId, accountId)
    }
}
