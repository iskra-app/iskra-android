package wtf.iskra.core.support

/**
 * Что делает Enter на *аппаратной* клавиатуре (`ComposerReturnKey`): отправляет или переносит строку. Чистое решение — чтобы проверялось без Android.
 */
object ReturnKey {
    enum class Action { SEND, NEWLINE }

    /** [sendsWithCtrl] — настройка; [ctrl], [shift] — какие клавиши зажаты вместе с Enter. */
    fun action(sendsWithCtrl: Boolean, ctrl: Boolean, shift: Boolean): Action = when {
        ctrl -> Action.SEND
        shift -> Action.NEWLINE
        sendsWithCtrl -> Action.NEWLINE
        else -> Action.SEND
    }
}
