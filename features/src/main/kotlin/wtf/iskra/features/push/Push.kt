package wtf.iskra.features.push

import android.content.Context
import wtf.iskra.core.notifications.NotificationActions
import wtf.iskra.core.notifications.PushDecoder
import wtf.iskra.core.notifications.PushNotification
import wtf.iskra.core.notifications.PushOutcome
import wtf.iskra.core.notifications.PushPayload
import wtf.iskra.core.notifications.PushTarget
import wtf.iskra.core.notifications.PushTargets
import wtf.iskra.core.support.AppSettings
import wtf.iskra.core.support.Log
import kotlin.time.Duration

/**
 * Единственная дверь пушей в приложение: оба транспорта (FCM, UnifiedPush) сходятся сюда и не знают,
 * что будет дальше; отсюда — расшифровка ([PushDecoder]) и уведомление ([PushPresenter]). Сам не знает,
 * каким транспортом пуш приехал (docs/PROMPT.md, 10.2).
 */
class Push(
    private val context: Context,
    private val decoder: PushDecoder,
    private val actions: NotificationActions,
    private val router: PushRouter,
    private val targets: PushTargets,
    settings: AppSettings,
    /** Транспорт этого flavor'а — из `:app`. */
    val transport: PushTransport,
    smallIcon: Int,
    /** Замок приложения: пока оно заперто, уведомление без имён и слов — «Новое сообщение». Null — без замка. */
    private val appLock: wtf.iskra.core.security.AppLock? = null,
) {
    private val presenter = PushPresenter(context.applicationContext, settings, smallIcon)

    /**
     * Пришёл пуш. Возвращается, когда уведомление показано (или решено не показывать) — транспорт
     * держит процесс живым до этого момента. [budget] — сколько транспорт даёт на расшифровку.
     *
     * Не успели в бюджет — заглушка сейчас и повтор фоновой задачей ([PushRetryJob]), которая тихо
     * поставит настоящее уведомление на её место. [isRetry] — это и есть тот повтор: второго не будет.
     */
    suspend fun handle(payload: PushPayload, budget: Duration = PushDecoder.INLINE_BUDGET, isRetry: Boolean = false) {
        val roomId = payload.roomId
        val eventId = payload.eventId
        if (roomId == null || eventId == null) {
            // Только счётчик. Ноль — всё прочитано где-то ещё, и уведомления аккаунта больше не новость.
            if (payload.unread == 0) presenter.clearAccount(payload.accountId)
            return
        }
        when (val outcome = decoder.notification(roomId, eventId, payload.accountId, budget)) {
            is PushOutcome.Show -> {
                val notification = outcome.notification
                if (appLock?.isLockedNow() == true) {
                    // Заперто: ни имени, ни слов, ни лица — то, что уведомление знает, знает только открытое приложение.
                    if (!isRetry && router.shouldPresent(notification.roomId, notification.accountId)) presenter.showFallback(roomId, payload.accountId)
                } else if (router.shouldPresent(notification.roomId, notification.accountId)) {
                    // Повтор встаёт на место заглушки, которая уже прозвенела: второй раз — молча.
                    presenter.show(notification, payload.unread, decoder.accountLabel(notification.accountId), alert = !isRetry)
                }
            }
            // Решили не показывать. Если на это место встала заглушка — она больше не нужна.
            is PushOutcome.Silence -> if (isRetry) presenter.clear(roomId, payload.accountId)
            // Не тишина: сообщение пришло, и молчать о нём хуже, чем сказать «новое сообщение».
            PushOutcome.NotSignedIn, is PushOutcome.Failed -> {
                Log.info(Log.Category.PUSH, "showing the fallback for a push that did not decrypt")
                if (!isRetry && router.shouldPresent(roomId, payload.accountId)) presenter.showFallback(roomId, payload.accountId)
                if (!isRetry && outcome is PushOutcome.Failed && outcome.timedOut) PushRetryJob.schedule(context, payload)
            }
        }
    }

    /** Транспорт выдал новый адрес (или отозвал его). Вошедший сеанс регистрирует его сам (`RootModel`). */
    fun targetChanged(target: PushTarget?) {
        Log.info(Log.Category.PUSH, if (target == null) "the push transport took its address back" else "the push transport gave us an address")
        targets.set(target)
    }

    /**
     * Проба `push-sample`: как выглядит уведомление — личное и групповое, с лицами-существами, — без
     * аккаунта и сервера. Слова пробы — не интерфейс: их видит только тот, кто её запустил.
     */
    internal fun sample() {
        presenter.show(
            PushNotification("!sample-direct", "\$1", "Аня", null, "жду на кухне", isNoisy = true, personName = "Аня", personId = "@anya:example.org"),
            unread = 1,
            accountLabel = null,
        )
        presenter.show(
            PushNotification("!sample-group", "\$2", "Кухня", "Борис", "кто взял мою кружку?", isNoisy = true, personName = "Борис", personId = "@boris:example.org"),
            unread = 2,
            accountLabel = null,
        )
    }

    /** «Ответить» из уведомления. */
    suspend fun reply(roomId: String, accountId: String, text: String) {
        if (actions.reply(accountId, roomId, text)) {
            presenter.replied(roomId, accountId, text)
        } else {
            Log.error(Log.Category.PUSH, "a reply from a notification did not go")
            // Поле ввода иначе крутит спиннер вечно; само сообщение осталось в очереди SDK.
            presenter.clear(roomId, accountId)
        }
    }

    /** «Прочитано» из уведомления. */
    suspend fun markRead(roomId: String, accountId: String) {
        presenter.clear(roomId, accountId)
        actions.markRead(accountId, roomId)
    }

    /** Чат открыли на экране: его уведомления сказали всё, что могли. */
    fun roomOpened(roomId: String, accountId: String?) = presenter.clear(roomId, accountId)

    /** Из аккаунта вышли: его уведомления и ярлыки разговоров уходят вместе с ним. */
    fun signedOut(accountId: String) = presenter.forget(accountId)
}
