package wtf.iskra.core.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Закреплённые комнаты. Снимок для экрана. */
data class PinnedState(
    /** Старые первыми, как отдаёт лента: *последнее* — самое свежее закреплённое, его и показывает полоса. */
    val messages: List<Message> = emptyList(),
    /** SDK ответил — *включая* «ничего». Пустой список и не пришедший — не одно и то же. */
    val hasLoaded: Boolean = false,
)

/**
 * Закреплённые сообщения комнаты как своя переписка. Порт `PinnedStore.swift`.
 *
 * Вторая лента той же комнаты: SDK даёт её сам (`TimelineFocus.PinnedEvents`), и это лучше, чем
 * держать список ID и доставать каждое, — кэш, пачки запросов и удаление закреплённого SDK уже ведёт.
 *
 * *Не* открывается с комнатой: в большинстве комнат не закреплено ничего, а лента, на которую никто не
 * смотрит, — всё равно подписка, которую SDK кормит. [TimelineStore.pinned] строит его по требованию.
 */
class PinnedStore internal constructor(
    /** Хендл комнаты — [TimelineStore]: он его и закрывает. */
    private val room: Room,
    private val ownUserId: String,
    private val strings: Strings,
    private val scope: CoroutineScope,
    /** Открепить не дали — сказать тем же «закреплённое не изменилось», что и меню сообщения. */
    private val onUnpinFailed: () -> Unit,
) {
    private val mutableState = MutableStateFlow(PinnedState())
    val state: StateFlow<PinnedState> = mutableState.asStateFlow()

    private var timeline: Timeline? = null
    private var listenerHandle: TaskHandle? = null
    private val items = mutableListOf<TimelineItem>()
    private val memo = TimelineMapper.Memo()

    /** Всё, что отдаёт лента, — до сверки с [roomPinned]. */
    private var mapped: List<Message> = emptyList()

    /**
     * Что закреплено по словам самой комнаты (`m.room.pinned_events` в `RoomInfo`). Лента закреплённых
     * SDK откреплённое не отпускает: на демо после открепления комната уже говорит «не закреплено», а
     * лента — и заново открытая, из кэша событий, — держит сообщение. Правда — у комнаты; лента даёт
     * только сами сообщения. Null — комната ещё не сказала.
     */
    private var pinnedIds: Set<String>? = null

    /** Как у [TimelineStore]: [stop] увеличивает, и пережившее await сверяет перед записью. */
    private var generation = 0
    private var opening: Deferred<Unit>? = null
    private var diffs: Channel<List<TimelineDiff>>? = null
    private var diffsJob: Job? = null
    private var isResyncing = false

    suspend fun start() {
        // Уже открывают — дождаться их: открытая ими лента и есть нужная.
        opening?.let {
            it.await()
            return
        }
        if (timeline != null) return
        val mine = generation
        val task = scope.async { open(mine) }
        opening = task
        try {
            task.await()
        } finally {
            if (mine == generation) opening = null
        }
    }

    private suspend fun open(mine: Int) {
        val configuration = TimelineConfiguration(
            focus = TimelineFocus.PinnedEvents,
            filter = TimelineFilter.All,
            internalIdPrefix = "pinned",
            dateDividerMode = DateDividerMode.DAILY,
            // «Прочитано» здесь не рисуется, а квитанции, по словам самого SDK, дороги.
            trackReadReceipts = TimelineReadReceiptTracking.DISABLED,
            reportUtds = false,
        )
        val built = try {
            withTimeLimit(SDK_LIMIT) { room.timelineWithConfiguration(configuration) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "закреплённые не открылись: ${Log.describe(error)}")
            if (mine == generation) mutableState.value = mutableState.value.copy(hasLoaded = true)
            return
        }
        if (mine != generation) {
            built.close()
            return
        }
        timeline = built

        // Одна очередь, в порядке SDK: диффы позиционные, как у основной ленты (см. `TimelineStore.open`).
        val channel = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
        diffs = channel
        diffsJob = scope.launch {
            for (batch in channel) {
                if (mine != generation) return@launch
                apply(batch)
            }
        }
        val handle = built.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                channel.trySend(diff)
            }
        })
        if (mine != generation) {
            handle.cancel()
            return
        }
        listenerHandle = handle
        // Пагинировать не нужно: лента закреплённых — это все закреплённые, первым диффом.
        mutableState.value = mutableState.value.copy(hasLoaded = true)
    }

    /** Кладёт ленту так, чтобы её можно было открыть снова (на iOS `stop()` когда-то оставлял тупик). */
    fun stop() {
        generation += 1
        opening = null
        listenerHandle?.cancel()
        listenerHandle = null
        diffs?.close()
        diffs = null
        diffsJob?.cancel()
        diffsJob = null
        timeline?.close()
        timeline = null
        items.clear()
        memo.clear()
        mapped = emptyList()
        mutableState.value = PinnedState(messages = mutableState.value.messages)
    }

    /**
     * Снять с закреплённых. Через эту ленту, а не основную: закреплённое может быть за тысячу
     * сообщений, а основная знает событие, только пока оно загружено.
     */
    suspend fun unpin(messageId: String) {
        val timeline = timeline ?: return
        try {
            withTimeLimit(SDK_LIMIT) { timeline.unpinEvent(messageId) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "открепить не вышло: ${Log.describe(error)}")
            onUnpinFailed()
        }
    }

    private fun apply(batch: List<TimelineDiff>) {
        when (val outcome = items.applyDiffs(batch.map { it.asListDiff() })) {
            is DiffOutcome.Desynced -> return resync(outcome.what)
            DiffOutcome.Applied -> Unit
        }
        // Только сообщения: четырём закреплённым дни отмечать незачем.
        mapped = memo.mapped(items, ownUserId, strings).mapNotNull { (it.body as? Mapped.Body.MessageBody)?.message }
        publish()
    }

    /** Комната сказала, что закреплено. Зовёт [TimelineStore] с каждым `RoomInfo`. */
    internal fun roomPinned(ids: List<String>) {
        pinnedIds = ids.toSet()
        publish()
    }

    private fun publish() {
        val ids = pinnedIds
        val messages = if (ids == null) mapped else mapped.filter { it.id in ids }
        mutableState.value = mutableState.value.copy(messages = messages)
    }

    /** Список разошёлся с SDK — перечитать: свежий слушатель начинает с `reset`, отчёта SDK о том, что он держит. */
    private fun resync(what: String) {
        if (isResyncing || timeline == null) return
        isResyncing = true
        Log.error(Log.Category.TIMELINE, "закреплённые разошлись с SDK: $what; перечитываем")
        scope.launch {
            try {
                stop()
                start()
            } finally {
                isResyncing = false
            }
        }
    }

    private companion object {
        val SDK_LIMIT = 30.seconds
    }
}
