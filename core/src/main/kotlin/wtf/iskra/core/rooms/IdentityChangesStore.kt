package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.IdentityStatusChange
import org.matrix.rustcomponents.sdk.IdentityStatusChangeListener
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_crypto.IdentityState
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Кто-то в этой комнате, чьи ключи — не те, что это устройство помнит. Порт `IdentityChange`. */
data class IdentityChange(
    val userId: String,
    val name: String,
    val kind: Kind,
) {
    enum class Kind {
        /**
         * Новые ключи у того, кого не проверяли. Обычное дело — новый телефон, переустановка, потерянный
         * ключ восстановления, — и в точности то, как выглядит сервер, вставший посередине. Какое из двух,
         * скажет только человек.
         */
        CHANGED,

        /** Новые ключи у того, кого этот аккаунт *проверял*. Серьёзный случай: проверенное больше не верно. */
        CHANGED_AFTER_VERIFICATION,
    }
}

/**
 * Люди одной комнаты, чья криптографическая личность изменилась, и единственное, что с каждым можно
 * сделать, — признать. Порт `IdentityChangesStore.swift`.
 *
 * Без этого приложение молчало: стратегия SDK по умолчанию отправляет заменённой личности без слова, то есть
 * тому, кто теперь держит эти ключи, — это нормально для человека с новым телефоном и есть вся атака, когда
 * не он. Здесь — собственный сигнал SDK (`Room.subscribeToIdentityStatusChanges`, тот, что Element X
 * показывает баннером), доведённый до экрана.
 *
 * Признать — не значит проверить. Новые ключи становятся теми, что ждём ([IdentityChangesStore.acknowledge] →
 * `pin()`), а у проверенного отзывается проверка, которая больше не верна (`withdrawVerification()`), — иначе
 * предупреждение вернётся. Слова на экране обязаны сказать это честно; этот тип только делает.
 */
class IdentityChangesStore internal constructor(
    /** Хендлы комнаты и шифрования — этого стора: закрывает их [stop]. */
    private val room: Room,
    private val encryption: Encryption,
    private val ownUserId: String,
    private val scope: CoroutineScope,
) {
    private val mutableChanges = MutableStateFlow<List<IdentityChange>>(emptyList())

    /** Старые первыми, как сообщали. Никогда не свой пользователь: смена своей личности — дело экрана восстановления. */
    val changes: StateFlow<List<IdentityChange>> = mutableChanges.asStateFlow()

    private var handle: TaskHandle? = null
    private var drain: Job? = null
    private var updates: Channel<List<IdentityStatusChange>>? = null

    /** [stop] увеличивает; пережившее await сверяет перед записью. */
    private var generation = 0
    private var started = false
    private var closed = false

    suspend fun start() {
        if (started || closed) return
        started = true
        val mine = generation

        // Одна упорядоченная дорога из потока Rust-колбэка, как везде, где приложение слушает SDK: «изменилось»
        // и отвечающее ему «закреплено», применённые наоборот, подняли бы предупреждение, которое только что сняли.
        val channel = Channel<List<IdentityStatusChange>>(Channel.UNLIMITED)
        updates = channel
        drain = scope.launch {
            for (batch in channel) {
                if (mine != generation) return@launch
                apply(batch, mine)
            }
        }

        val subscribed = try {
            withTimeLimit(SDK_LIMIT) {
                room.subscribeToIdentityStatusChanges(object : IdentityStatusChangeListener {
                    override fun call(identityStatusChange: List<IdentityStatusChange>) {
                        channel.trySend(identityStatusChange)
                    }
                })
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "смена ключей не подписалась: ${Log.describe(error)}")
            null
        }
        // Остановили, пока подписывались: вернувшийся хендл уже ничей.
        if (mine != generation) {
            subscribed?.cancel()
            return
        }
        handle = subscribed
    }

    /** Экран ушёл: подписка и хендлы отпускаются, они нативные. Открыть снова нельзя — нужен новый стор. */
    fun stop() {
        generation += 1
        handle?.cancel()
        handle = null
        updates?.close()
        updates = null
        drain?.cancel()
        drain = null
        mutableChanges.value = emptyList()
        started = false
        if (!closed) {
            closed = true
            encryption.close()
            room.close()
        }
    }

    /**
     * «Понятно» — новые ключи становятся теми, что ждём, и предупреждение уходит. Бросает, а не молчит, если
     * личности нет или SDK отказал: предупреждение, которое будто ушло и вернулось при следующем запуске, хуже
     * того, что осталось и сказало почему.
     */
    suspend fun acknowledge(change: IdentityChange) {
        try {
            val identity = withTimeLimit(SDK_LIMIT) { encryption.userIdentity(change.userId, false) }
                ?: throw IdentityChangeException("unknown identity")
            withTimeLimit(SDK_LIMIT) {
                when (change.kind) {
                    IdentityChange.Kind.CHANGED -> identity.pin()
                    IdentityChange.Kind.CHANGED_AFTER_VERIFICATION -> identity.withdrawVerification()
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is IdentityChangeException) throw error
            Log.error(Log.Category.ROOMS, "ключи не закрепились: ${Log.describe(error)}")
            throw IdentityChangeException("sdk refused", error)
        }
        // SDK скажет то же `PINNED`; снять здесь тоже — значит баннер уходит в миг нажатия, а не через синхронизацию.
        mutableChanges.value = IdentityChanges.without(mutableChanges.value, change.userId)
    }

    private suspend fun apply(batch: List<IdentityStatusChange>, mine: Int) {
        for (update in batch) {
            if (update.userId == ownUserId) continue
            val kind = IdentityChanges.kind(update.changedTo)
            if (kind == null) {
                mutableChanges.value = IdentityChanges.without(mutableChanges.value, update.userId)
                continue
            }
            val name = displayName(update.userId)
            // Остановили, пока искали имя.
            if (mine != generation) return
            mutableChanges.value = IdentityChanges.with(mutableChanges.value, IdentityChange(update.userId, name, kind))
        }
    }

    /** Имя, которым его знает комната, и Matrix ID, если имени нет. */
    private suspend fun displayName(userId: String): String =
        try {
            withTimeLimit(SDK_LIMIT) { room.member(userId) }.displayName ?: userId
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            userId
        }

    private companion object {
        val SDK_LIMIT = 30.seconds
    }
}

class IdentityChangeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Чистая половина: что из сообщения SDK делает баннер. С тестами `IdentityChangesTest`. */
internal object IdentityChanges {
    /** Null — личность в порядке (закреплена или проверена): предупреждение, если было, снимается. */
    fun kind(state: IdentityState): IdentityChange.Kind? = when (state) {
        IdentityState.PIN_VIOLATION -> IdentityChange.Kind.CHANGED
        IdentityState.VERIFICATION_VIOLATION -> IdentityChange.Kind.CHANGED_AFTER_VERIFICATION
        IdentityState.PINNED, IdentityState.VERIFIED -> null
    }

    /** Новое сообщение о том же человеке заменяет прежнее на его месте; о новом — встаёт в конец. */
    fun with(changes: List<IdentityChange>, change: IdentityChange): List<IdentityChange> {
        val index = changes.indexOfFirst { it.userId == change.userId }
        return if (index >= 0) changes.toMutableList().also { it[index] = change } else changes + change
    }

    fun without(changes: List<IdentityChange>, userId: String): List<IdentityChange> =
        if (changes.any { it.userId == userId }) changes.filterNot { it.userId == userId } else changes
}
