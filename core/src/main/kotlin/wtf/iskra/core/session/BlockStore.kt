package wtf.iskra.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.IgnoredUsersListener
import org.matrix.rustcomponents.sdk.TaskHandle
import wtf.iskra.core.R
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Что не вышло с блокировкой — словами для человека. */
enum class BlockFailure(val message: Int) {
    BLOCK(R.string.account_block_failed),
    UNBLOCK(R.string.account_unblock_failed),
}

/**
 * Кого человек заблокировал и два действия, что это меняют. Порт `BlockStore`.
 *
 * «Заблокировать» в приложении — это *ignore* Matrix: список на сервере, поэтому он идёт за аккаунтом на все устройства, а сам
 * сервер перестаёт доставлять сообщения заблокированного в sync. SDK прячет их и в уже открытых лентах. Поэтому блокировка — один
 * вызов, а не учёт по комнатам: это факт об аккаунте, а не об одном разговоре.
 *
 * Она односторонняя, и это честно: блокировка прячет *его от вас*. У Matrix нет способа запретить человеку писать — ignore про то,
 * что вы получаете. Интерфейс говорит «заблокировать», потому что это слово, за которым тянутся, но не обещает больше протокола.
 */
class BlockStore internal constructor(private val client: Client, private val scope: CoroutineScope) {
    data class State(val blockedUserIds: List<String> = emptyList(), val hasLoaded: Boolean = false, val failure: BlockFailure? = null)

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    private var subscription: TaskHandle? = null

    /** Список сейчас, а затем подписка на каждое изменение: блокировка с другого устройства появляется и здесь. */
    fun start() {
        if (subscription != null) return
        subscription = client.subscribeToIgnoredUsers(object : IgnoredUsersListener {
            override fun call(ignoredUserIds: List<String>) {
                mutable.update { it.copy(blockedUserIds = ignoredUserIds.sorted(), hasLoaded = true) }
            }
        })
        scope.launch {
            val ids = runCatching { withTimeLimit(LIMIT) { client.ignoredUsers() } }.getOrNull() ?: return@launch
            mutable.update { it.copy(blockedUserIds = ids.sorted(), hasLoaded = true) }
        }
    }

    fun stop() {
        subscription?.cancel()
        subscription = null
    }

    /** Блокирует — `ignoreUser`: сообщения перестают приходить, а те, что на экране, SDK прячет. */
    suspend fun block(userId: String) = change(BlockFailure.BLOCK, "блокировка") { client.ignoreUser(userId) }

    /** Снимает блокировку. */
    suspend fun unblock(userId: String) = change(BlockFailure.UNBLOCK, "снятие блокировки") { client.unignoreUser(userId) }

    fun isBlocked(userId: String): Boolean = userId in mutable.value.blockedUserIds

    fun failureShown() = mutable.update { it.copy(failure = null) }

    private suspend fun change(failure: BlockFailure, what: String, call: suspend () -> Unit) {
        try {
            withTimeLimit(LIMIT) { call() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "$what не вышла: ${Log.describe(error)}")
            mutable.update { it.copy(failure = failure) }
        }
    }

    private companion object {
        val LIMIT = 30.seconds
    }
}
