package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import wtf.iskra.core.timeline.DiffOutcome
import wtf.iskra.core.timeline.TimelineMapper
import wtf.iskra.core.timeline.TimelineRows
import wtf.iskra.core.timeline.applyDiffs
import wtf.iskra.core.timeline.asListDiff
import kotlin.time.Duration.Companion.seconds

/**
 * Живая лента одной комнаты, пока её строка на экране — см. [PeekLine]. Порт `RoomPeek.swift`.
 *
 * Лента на строку звучит дорого и не дорога: это лента на *видимую* строку — список просит её, когда строка
 * появляется, и отпускает самые давние, когда их больше `RoomListStore.PEEK_LIMIT`. Двадцать элементов из кэша
 * событий на устройстве, истории глубже нет.
 *
 * Хендл комнаты — списка: он его и закрывает, [stop] закрывает только ленту.
 */
internal class RoomPeek(
    private val room: Room,
    private val ownUserId: String,
    private val strings: Strings,
    private val scope: CoroutineScope,
) {
    var line: PeekLine? = null
        private set

    /** Строке есть что сказать нового. */
    var onChange: (() -> Unit)? = null

    private var timeline: Timeline? = null
    private val items = mutableListOf<TimelineItem>()
    private val memo = TimelineMapper.Memo()
    private var handle: TaskHandle? = null
    private var diffs: Channel<List<TimelineDiff>>? = null
    private var diffsJob: Job? = null
    private var stopped = false

    suspend fun start() {
        val configuration = TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false),
            filter = TimelineFilter.All,
            internalIdPrefix = "peek",
            dateDividerMode = DateDividerMode.DAILY,
            // Единственное, чего не скажет пересказ SDK, и половина причин, по которым это есть.
            trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS,
            reportUtds = false,
        )
        val built = try {
            withTimeLimit(SDK_LIMIT) { room.timelineWithConfiguration(configuration) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "заглянуть в чат не вышло: ${Log.describe(error)}")
            return
        }
        if (stopped) {
            built.close()
            return
        }
        timeline = built

        // Одна очередь, один потребитель, в порядке SDK — см. `TimelineStore.open()`.
        val channel = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
        diffs = channel
        diffsJob = scope.launch {
            for (batch in channel) apply(batch)
        }
        val listening = built.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                channel.trySend(diff)
            }
        })
        if (stopped) {
            listening.cancel()
            return
        }
        handle = listening

        // Живая лента открывается пустой: одна страница из кэша событий.
        runCatching { withTimeLimit(SDK_LIMIT) { built.paginateBackwards(PAGE_SIZE.toUShort()) } }
    }

    fun stop() {
        stopped = true
        handle?.cancel()
        handle = null
        diffs?.close()
        diffs = null
        diffsJob?.cancel()
        diffsJob = null
        timeline?.close()
        timeline = null
        items.clear()
        memo.clear()
    }

    private fun apply(batch: List<TimelineDiff>) {
        if (stopped) return
        when (val outcome = items.applyDiffs(batch.map { it.asListDiff() })) {
            is DiffOutcome.Desynced -> {
                // Заново себя не стоит: строка вернётся к пересказу SDK, а при следующем появлении заглянет снова.
                Log.error(Log.Category.ROOMS, "заглядывание в чат разошлось с SDK: ${outcome.what}")
                items.clear()
                memo.clear()
                line = null
                onChange?.invoke()
            }
            DiffOutcome.Applied -> update()
        }
    }

    private fun update() {
        val rows = TimelineRows.rows(memo.mapped(items, ownUserId, strings))
        val next = PeekLine.last(rows, strings)
        if (next == line) return
        line = next
        onChange?.invoke()
    }

    private companion object {
        /** Сколько просить разом: хватает пройти серию реакций и квитанций до чего-то со словами. */
        const val PAGE_SIZE = 20
        val SDK_LIMIT = 30.seconds
    }
}
