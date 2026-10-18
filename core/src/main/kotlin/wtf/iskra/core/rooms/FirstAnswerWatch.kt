package wtf.iskra.core.rooms

import org.matrix.rustcomponents.sdk.RoomListServiceState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Правила «Обновление…» и «Ожидание сети», пока синхронизация поднялась, а сервер ещё не ответил.
 * Таймер — в [RoomListStore]; здесь правила, о которых можно спросить без клиента. Порт
 * `FirstAnswerWatch.swift`.
 */
internal object FirstAnswerWatch {
    /** Те же полсекунды, что у индикатора SDK: догонялка, кончившаяся раньше, чем слово прочтут, его не мигнёт. */
    val updatingAfter: Duration = 500.milliseconds

    /**
     * Когда «Обновление…» сменяется на «Ожидание сети». SDK даёт одному запросу синхронизации минуту
     * и потом повторяет; к четверти этого смотрящий на неподвижный список заслуживает объяснения.
     */
    val stalledAfter: Duration = 12.seconds

    /**
     * Записывает ли SDK это состояние только *после* ответа сервера. `SettingUp`, `Recovering` и
     * `Running` значат, что сервер ответил с тех пор, как синхронизация поднялась; `Terminated`
     * оставляет пауза, `Error` — неудачный запрос, `Initial` — список, который ещё не спрашивал.
     */
    fun answered(state: RoomListServiceState): Boolean = when (state) {
        RoomListServiceState.SETTING_UP, RoomListServiceState.RECOVERING, RoomListServiceState.RUNNING -> true
        RoomListServiceState.INITIAL, RoomListServiceState.TERMINATED, RoomListServiceState.ERROR -> false
    }

    /** Есть ли чего ждать. Не пока синхронизация опущена нарочно и не когда SDK уже сказал «офлайн». */
    fun isWaiting(stopped: Boolean, paused: Boolean, offline: Boolean, answered: Boolean): Boolean =
        !stopped && !paused && !offline && !answered
}
