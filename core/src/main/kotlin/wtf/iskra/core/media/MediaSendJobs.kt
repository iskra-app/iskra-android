package wtf.iskra.core.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueListener
import org.matrix.rustcomponents.sdk.TaskHandle
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Фото, видео и файлы на пути вверх — от нажатия «отправить» и до того, как сервер получил каждое, где бы человек ни
 * оказался за это время. Порт `MediaSendJobs`.
 *
 * Принадлежит *сеансу*, а не комнате, и это нарочно. Стор переписки живёт, пока жив её экран, а тот, кто отправил
 * двухминутное видео и ушёл в список чатов, не перестал интересоваться, дошло ли оно. Поэтому слушает очередь отправки
 * комнаты сам (`Room.subscribeToSendQueueUpdates`), пока там что-то уходит, — и то, что он знает, и рисует уведомление
 * foreground-сервиса (`MediaSendService`), которое держит процесс живым, пока идёт загрузка.
 *
 * *Задача* — одно нажатие «отправить»: видео, альбом, большой файл. SDK знает транзакции — по одной на событие, — так что
 * задача — те транзакции, что очередь объявила (`NewLocalEvent`), пока наши отправки ей передавались. Это хрупкий шов
 * всего устройства, и правило держится простым, чтобы быть честным: следующие транзакции, что появятся в этой комнате, до
 * числа принятых отправок, — задачи.
 *
 * Две фазы, потому что до первого байта проходит настоящее время: видео сжимается и очищается (`VideoPreparation`), а
 * полоса, стоящая на нуле полминуты, неотличима от застрявшей. Подготовка — первые ~30 %, загрузка — остальное.
 *
 * Не всё — задача: одно фото уходит раньше, чем кто-то успел бы взглянуть на полосу, — следуем, только когда стоит
 * ([isWorthFollowing]).
 */
class MediaSendJobs internal constructor(
    /** Хендл комнаты для подписки; закрывает его тот, кто подписался, когда отпускает. */
    private val roomFor: (String) -> Room?,
    private val look: (String) -> RoomLook? = { null },
    private val scope: CoroutineScope,
) {
    /** Что уходит, словами индикатора: «видео», «5 фото», «файл». */
    sealed interface What {
        data object Video : What
        data class Photos(val count: Int) : What
        data object File : What

        /** Фото и видео вместе — альбом с роликом. */
        data class Media(val count: Int) : What
    }

    enum class Phase {
        /** Сжимается и очищается, очереди ещё нечего. */
        PREPARING,

        /** В очереди, идёт вверх. */
        UPLOADING,

        /** Всё на сервере. */
        SENT,

        /** Очередь отказалась навсегда: «Не отправлено», а чат — одно нажатие. */
        FAILED,
    }

    /** Одна отправляемая вещь — только чтобы решить, следовать ли за ней. */
    data class Item(val isVideo: Boolean = false, val isFile: Boolean = false, val bytes: Long = 0)

    /** Кто такая комната и как выглядит — для индикатора. */
    data class RoomLook(val name: String, val avatarSeed: String)

    data class Job(
        val id: String,
        val roomId: String,
        val roomName: String,
        val avatarSeed: String,
        val what: What,
        val phase: Phase = Phase.PREPARING,
        val startedAt: Long,
        /** Сколько отправок задача начала и сколько передано очереди (принято или нет). */
        internal val expected: Int,
        internal val handed: Int = 0,
        internal val accepted: Int = 0,
        internal val handingDone: Boolean = false,
        /** Транзакции очереди — этой задачи — и докуда каждая дошла (0…1). */
        internal val uploads: Map<String, Double> = emptyMap(),
        internal val sent: Set<String> = emptySet(),
    ) {
        /** Всё вместе, 0…1: подготовка весит [PREPARATION_SHARE], загрузка — остальное. */
        val progress: Double
            get() {
                if (expected <= 0) return 1.0
                val prepared = handed.toDouble() / expected
                val uploaded = uploads.values.sum() / maxOf(accepted, 1)
                return minOf(1.0, PREPARATION_SHARE * prepared + (1 - PREPARATION_SHARE) * (if (handingDone && accepted == 0) 1.0 else uploaded))
            }

        val isFinished: Boolean get() = phase == Phase.SENT || phase == Phase.FAILED
    }

    /** Что сказала очередь, словами этого файла — чтобы учёт проверялся без комнаты на Rust. */
    internal sealed interface QueueEvent {
        data class Created(val transaction: String) : QueueEvent
        data class Progress(val transaction: String, val fraction: Double) : QueueEvent
        data class Sent(val transaction: String) : QueueEvent
        data class Failed(val transaction: String, val recoverable: Boolean) : QueueEvent
        data class Cancelled(val transaction: String) : QueueEvent
    }

    private val mutableJobs = MutableStateFlow<List<Job>>(emptyList())

    /** Задачи сейчас. Поток, а не колбэк: на нём живёт уведомление, когда экрана, что наблюдал бы, нет. */
    val jobs: StateFlow<List<Job>> = mutableJobs.asStateFlow()

    private class Subscription(val room: Room, val handle: TaskHandle)

    private val subscriptions = HashMap<String, Subscription>()
    private val pendingSubscriptions = HashSet<String>()
    private val cleanup = HashMap<String, CoroutineJob>()

    // MARK: Начало и передача

    /**
     * Начинает следить за отправкой [items] в комнату — или ничего не делает и возвращает null, когда не стоит. Зовётся
     * до подготовки первой вещи, чтобы подготовка была видна.
     */
    fun begin(roomId: String, items: List<Item>, now: Long = System.currentTimeMillis()): String? {
        if (!isWorthFollowing(items)) return null
        val room = look(roomId)
        val videos = items.count { it.isVideo }
        val what = when {
            items.size == 1 -> if (videos == 1) What.Video else if (items[0].isFile) What.File else What.Photos(1)
            videos == 0 -> if (items.all { it.isFile }) What.File else What.Photos(items.size)
            else -> What.Media(items.size)
        }
        val job = Job(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            roomName = room?.name.orEmpty(),
            avatarSeed = room?.avatarSeed ?: roomId,
            what = what,
            startedAt = now,
            expected = items.size,
        )
        mutableJobs.update { it + job }
        subscribe(roomId)
        return job.id
    }

    /** Одна вещь передана очереди — [accepted] false, когда SDK отказал сразу, и ждать транзакцию нечего. */
    fun handed(id: String?, accepted: Boolean) = update(id) { it.copy(handed = it.handed + 1, accepted = it.accepted + if (accepted) 1 else 0) }

    /** Всё передано; остальное — очереди. */
    fun doneHanding(id: String?) = update(id) { it.copy(handingDone = true, handed = it.expected) }

    // MARK: Очередь

    internal fun receive(event: QueueEvent, roomId: String) {
        when (event) {
            is QueueEvent.Created -> mutableJobs.update { jobs ->
                // Самая давняя задача этой комнаты, которой ещё должны транзакцию, берёт её.
                val index = jobs.indexOfFirst {
                    it.roomId == roomId && !it.isFinished && it.uploads.size < (if (it.handingDone) it.accepted else maxOf(it.accepted, it.handed + 1))
                }
                if (index < 0) jobs else jobs.toMutableList().also {
                    it[index] = it[index].copy(uploads = it[index].uploads + (event.transaction to 0.0), phase = Phase.UPLOADING)
                }
            }
            is QueueEvent.Progress -> withJob(event.transaction) { it.copy(uploads = it.uploads + (event.transaction to event.fraction.coerceIn(0.0, 0.99))) }
            is QueueEvent.Sent -> withJob(event.transaction) { it.copy(uploads = it.uploads + (event.transaction to 1.0), sent = it.sent + event.transaction) }
            // Восстановимая ошибка — очередь встала на сеть и пойдёт сама (`SendQueueRecovery`); провалом показывается
            // только окончательный отказ.
            is QueueEvent.Failed -> if (!event.recoverable) withJob(event.transaction) { it.copy(phase = Phase.FAILED) }
            is QueueEvent.Cancelled -> withJob(event.transaction) { it.copy(uploads = it.uploads - event.transaction, accepted = maxOf(0, it.accepted - 1)) }
        }
        settleAll()
    }

    private fun withJob(transaction: String, change: (Job) -> Job) {
        mutableJobs.update { jobs ->
            val index = jobs.indexOfFirst { transaction in it.uploads }
            if (index < 0) jobs else jobs.toMutableList().also { it[index] = change(it[index]) }
        }
    }

    private fun update(id: String?, change: (Job) -> Job) {
        if (id == null) return
        mutableJobs.update { jobs ->
            val index = jobs.indexOfFirst { it.id == id }
            if (index < 0) jobs else jobs.toMutableList().also { it[index] = change(it[index]) }
        }
        settleAll()
    }

    /**
     * Кончено, когда каждая принятая вещь на сервере, — или когда не принято ни одной, а это провал, который экран
     * комнаты уже сказал словами.
     */
    private fun settleAll() {
        val settled = mutableJobs.value.map { job ->
            if (job.phase != Phase.FAILED && job.handingDone) {
                when {
                    job.accepted == 0 -> job.copy(phase = Phase.FAILED)
                    job.sent.size >= job.accepted -> job.copy(phase = Phase.SENT)
                    else -> job
                }
            } else {
                job
            }
        }
        mutableJobs.value = settled
        settled.filter { it.isFinished }.forEach(::scheduleRemoval)
    }

    /**
     * Законченная задача лежит несколько секунд — чтобы «Отправлено» или «Не отправлено» успели прочесть, — и уходит; и
     * комнату перестают слушать, когда в ней ничего не осталось.
     */
    private fun scheduleRemoval(job: Job) {
        if (cleanup.containsKey(job.id)) return
        val linger = if (job.phase == Phase.SENT) SENT_LINGERS else FAILED_LINGERS
        cleanup[job.id] = scope.launch {
            delay(linger)
            mutableJobs.update { jobs -> jobs.filterNot { it.id == job.id } }
            cleanup.remove(job.id)
            if (mutableJobs.value.none { it.roomId == job.roomId }) release(job.roomId)
        }
    }

    private fun subscribe(roomId: String) {
        if (roomId in subscriptions || !pendingSubscriptions.add(roomId)) return
        val room = roomFor(roomId)
        if (room == null) {
            pendingSubscriptions.remove(roomId)
            return
        }
        scope.launch {
            val handle = try {
                withTimeLimit(SDK_LIMIT) {
                    room.subscribeToSendQueueUpdates(object : SendQueueListener {
                        override fun onUpdate(update: RoomSendQueueUpdate) {
                            val event = event(update) ?: return
                            scope.launch { receive(event, roomId) }
                        }
                    })
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.MEDIA, "очередь отправки не подписалась: ${Log.describe(error)}")
                null
            }
            pendingSubscriptions.remove(roomId)
            // Пока подписывались, комнату могли отпустить: хендл, что пришёл, уже ничей.
            if (handle == null || roomId in subscriptions || mutableJobs.value.none { it.roomId == roomId }) {
                handle?.cancel()
                room.close()
                return@launch
            }
            subscriptions[roomId] = Subscription(room, handle)
        }
    }

    private fun release(roomId: String) {
        subscriptions.remove(roomId)?.let {
            it.handle.cancel()
            it.room.close()
        }
    }

    /** Всё — для уходящего сеанса. */
    fun stop() {
        subscriptions.keys.toList().forEach(::release)
        cleanup.values.forEach { it.cancel() }
        cleanup.clear()
        mutableJobs.value = emptyList()
    }

    companion object {
        /** Видео, три картинки и больше, или файл больше ~10 МБ. Меньше — уйдёт, пока индикатор успел бы что-то сказать. */
        fun isWorthFollowing(items: List<Item>): Boolean =
            items.any { it.isVideo } || items.size >= 3 || items.any { it.bytes > BIG_FILE }

        const val BIG_FILE = 10L * 1024 * 1024
        const val PREPARATION_SHARE = 0.3
        private val SENT_LINGERS = 4.seconds
        private val FAILED_LINGERS = 30.seconds
        private val SDK_LIMIT = 30.seconds

        /** Слова SDK → слова учёта. Прочее (замена, повтор) учёту ничего не говорит. */
        internal fun event(update: RoomSendQueueUpdate): QueueEvent? = when (update) {
            is RoomSendQueueUpdate.NewLocalEvent -> QueueEvent.Created(update.transactionId)
            is RoomSendQueueUpdate.MediaUpload ->
                if (update.progress.total > 0uL) QueueEvent.Progress(update.relatedTo, update.progress.current.toDouble() / update.progress.total.toDouble()) else null
            is RoomSendQueueUpdate.SentEvent -> QueueEvent.Sent(update.transactionId)
            is RoomSendQueueUpdate.SendError -> QueueEvent.Failed(update.transactionId, update.isRecoverable)
            is RoomSendQueueUpdate.CancelledLocalEvent -> QueueEvent.Cancelled(update.transactionId)
            else -> null
        }
    }
}
