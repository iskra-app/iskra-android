package wtf.iskra.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import wtf.iskra.core.rooms.MatrixLink

/**
 * Ссылка на человека или комнату, открытая снаружи — нажатая в другом приложении, из камеры, из браузера, — пока корень
 * её не заберёт. Тот же приём, что у `PushRouter`: холодный запуск покрыт, ссылка приходит раньше экранов и ждёт, пока
 * появится дерево вошедшего.
 */
class LinkRouter {
    private val pending = MutableStateFlow<MatrixLink?>(null)

    /** Ждущая ссылка — чтобы корень заметил её и в уже открытом приложении. */
    val request: StateFlow<MatrixLink?> = pending.asStateFlow()

    /** Пришёл адрес. Всё, что не ссылка Matrix, молча отбрасывается: сюда ведут только наши фильтры, но не только своё в них попадает. */
    fun opened(address: String?): Boolean {
        val link = address?.let(MatrixLink::parse) ?: return false
        pending.value = link
        return true
    }

    /** Забирает ждущую ссылку и забывает её: второй возврат в приложение не откроет её снова. */
    fun take(): MatrixLink? = pending.getAndUpdate { null }
}
