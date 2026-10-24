package wtf.iskra.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import wtf.iskra.core.R
import wtf.iskra.core.support.Log

/** Что на экране «Устройства» не вышло — словами для человека. */
enum class DevicesActionFailure(val message: Int) {
    /** Список не загрузился. */
    LOAD(R.string.devices_load_failed),

    /** Сессия не закончилась. */
    END(R.string.devices_end_failed),

    /** Пароль, который спросил сервер, — не пароль аккаунта. */
    WRONG_PASSWORD(R.string.devices_wrong_password),

    /** Сервер заканчивает сессии на своей странице и не сказал, где она. */
    MANAGED_ELSEWHERE(R.string.devices_managed_elsewhere),
}

/**
 * Экран «Устройства»: это устройство, остальные сессии аккаунта и их завершение — Settings → Devices в Telegram. Откуда
 * список — см. [AccountDevices]. Порт `DevicesStore`.
 */
class DevicesStore(private val source: AccountDevices) {
    data class State(
        val devices: List<AccountDevice> = emptyList(),
        val hasLoaded: Boolean = false,
        val isLoading: Boolean = false,
        /** Сессии, что заканчиваются сейчас, — чтобы их строки это показали. */
        val ending: Set<String> = emptySet(),
        val failure: DevicesActionFailure? = null,
        /** Сессии, которые сервер закончит, только получив пароль аккаунта: экран спросит и ответит [end] с паролем. */
        val awaitingPassword: List<String>? = null,
        /** Страница сервера, где сессии заканчиваются; её откроет экран (браузер у него) и сбросит [pageOpened]. */
        val pageToOpen: String? = null,
    ) {
        /** Сессия, в которой работает приложение, — наверху экрана. */
        val current: AccountDevice? get() = devices.firstOrNull { it.isCurrent }

        /** Все остальные, недавно виденные первыми. */
        val others: List<AccountDevice> get() = devices.filterNot { it.isCurrent }
    }

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    /** Заканчивается ли всё на странице сервера, а не здесь, — сказано на экране, чтобы браузер не был сюрпризом. */
    val isManagedByServer: Boolean get() = source.isManagedByServer

    suspend fun load() {
        mutable.update { it.copy(isLoading = true) }
        try {
            val devices = source.list()
            mutable.update { it.copy(devices = devices, hasLoaded = true, isLoading = false) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Список, что не пришёл, называется один раз, и старый остаётся на экране, а не обнуляется: минуту назад он был
            // правдой, и это лучшая картина.
            Log.error(Log.Category.SESSION, "список сессий не загрузился: ${Log.describe(error)}")
            mutable.update { it.copy(isLoading = false, failure = if (it.hasLoaded) it.failure else DevicesActionFailure.LOAD) }
        }
    }

    /**
     * Заканчивает эти сессии. Строки уходят, когда сервер согласился, не раньше: сессия, что пропала с экрана, а потом
     * оказалась всё ещё в аккаунте, — худший ответ.
     */
    suspend fun end(ids: List<String>, password: String? = null) {
        mutable.update { it.copy(ending = it.ending + ids) }
        try {
            source.end(ids, password)
            mutable.update { state -> state.copy(devices = state.devices.filterNot { it.id in ids }) }
            // И правда от сервера, который мог закончить чуть больше или чуть меньше.
            load()
        } catch (error: AccountDevicesException) {
            mutable.update { state ->
                when (val reason = error.error) {
                    AccountDevicesError.NeedsPassword -> state.copy(awaitingPassword = ids)
                    AccountDevicesError.WrongPassword -> state.copy(failure = DevicesActionFailure.WRONG_PASSWORD)
                    is AccountDevicesError.ManagedByServer ->
                        if (reason.page != null) state.copy(pageToOpen = reason.page) else state.copy(failure = DevicesActionFailure.MANAGED_ELSEWHERE)
                    AccountDevicesError.Failed -> state.copy(failure = DevicesActionFailure.END)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "сессия не закончилась: ${Log.describe(error)}")
            mutable.update { it.copy(failure = DevicesActionFailure.END) }
        } finally {
            mutable.update { it.copy(ending = it.ending - ids.toSet()) }
        }
    }

    /** «Завершить все остальные сессии» — красная кнопка Telegram. */
    suspend fun endOthers(password: String? = null) = end(mutable.value.others.map { it.id }, password)

    fun failureShown() = mutable.update { it.copy(failure = null) }

    fun passwordDismissed() = mutable.update { it.copy(awaitingPassword = null) }

    fun pageOpened() = mutable.update { it.copy(pageToOpen = null) }
}
