package wtf.iskra.features.push

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Что происходит по нажатию на уведомление и показывать ли его вообще. Порт `PushRouter.swift`.
 *
 * Уведомление «Аня: приду через час», открывающее список чатов, заставляет искать дважды. Поэтому
 * нажатие держится здесь, пока его не заберут: холодный запуск тоже покрыт — нажатие приходит раньше
 * экранов, [request] просто ждёт, и корень читает его, когда появится дерево вошедшего.
 *
 * Один на процесс: `MainActivity` получает нажатие, `PushPresenter` спрашивает про видимый чат, и оба
 * должны говорить с одним и тем же.
 */
class PushRouter {
    /** Чат, о котором просило нажатое уведомление, и чей он, — пока не открыт. */
    data class Request(val roomId: String, val accountId: String?)

    private val pending = MutableStateFlow<Request?>(null)

    /** Ждущее нажатие — чтобы корень заметил его и в уже открытом приложении. */
    val request: StateFlow<Request?> = pending.asStateFlow()

    /** Чат на экране сейчас, если есть. Уведомление о чате, который человек и так видит, — не новость. */
    @Volatile
    var visibleRoomId: String? = null

    /**
     * Чей чат на экране: сообщение в открытом чате *другого* аккаунта на этом телефоне всё равно
     * заслуживает уведомления. Два человека могут быть в одной комнате.
     */
    @Volatile
    var visibleAccountId: String? = null

    /** Нажали уведомление. Зовёт `MainActivity`. */
    fun tapped(roomId: String, accountId: String? = null) {
        pending.value = Request(roomId, accountId)
    }

    /**
     * Забирает ждущий чат и забывает его — чтобы второй возврат в приложение не открыл снова разговор,
     * из которого человек с тех пор ушёл.
     */
    fun takeRequest(): Request? = pending.getAndUpdate { null }

    /**
     * Пришёл пуш, пока приложение на экране: показывать ли. Всё, кроме уже открытого чата — сообщение
     * и так появляется в ленте за баннером, и баннер был бы тем же предложением дважды, а звук — второй
     * тревогой об одном событии. Пуш без комнаты показывается: непонятное уведомление — меньший сбой,
     * чем пропавшее.
     */
    fun shouldPresent(roomId: String?, accountId: String? = null): Boolean {
        if (roomId == null || roomId != visibleRoomId) return true
        return accountId != null && accountId != visibleAccountId
    }
}
