package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import wtf.iskra.core.R
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import wtf.iskra.core.timeline.DiffOutcome
import wtf.iskra.core.timeline.applyDiffs
import wtf.iskra.core.timeline.asListDiff
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.NotificationSettings
import org.matrix.rustcomponents.sdk.NotificationSettingsDelegate
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListServiceState
import org.matrix.rustcomponents.sdk.RoomListServiceStateListener
import org.matrix.rustcomponents.sdk.RoomListServiceSyncIndicator
import org.matrix.rustcomponents.sdk.RoomListServiceSyncIndicatorListener
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueRoomErrorListener
import org.matrix.rustcomponents.sdk.SendQueueRoomUpdateListener
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Что список показывает. Снимок для экрана: ни одного типа SDK. */
data class RoomListState(
    /** Новые сверху (закреплённые выше), без архива. */
    val rooms: List<RoomSummary> = emptyList(),
    /** Смахнутые в архив (`m.lowpriority`): всё ещё вступленные, с историей, просто не в главном списке. */
    val archivedRooms: List<RoomSummary> = emptyList(),
    /** До первой пачки — чтобы экран сказал что-то добрее пустого списка. */
    val isLoadingInitialRooms: Boolean = true,
    /** SDK сказал, что сервер недоступен, и на экране — то, что уже было на устройстве. */
    val isOffline: Boolean = false,
    /** Список догоняет сервер — «Обновление…» в заголовке, как в Telegram. */
    val isUpdating: Boolean = false,
    /** Сервер спрошен и молчит так долго, что честное слово — «Ожидание сети». */
    val isStalled: Boolean = false,
    /** Действие из строки списка, которое не удалось. Полем, а не броском: в строке списка нет стека. */
    val failure: RoomActionFailure? = null,
) {
    /** Что экраны называют «нет сети»: так сказал SDK, или сервер молчит слишком долго. */
    val isUnreachable: Boolean get() = isOffline || isStalled
}

/** Действие над комнатой, которое не удалось, — словами, которые можно показать. */
enum class RoomActionFailure(val message: Int) {
    ACCEPT_INVITE(R.string.room_action_accept_invite_failed),
    DECLINE_INVITE(R.string.room_action_decline_invite_failed),
    PIN(R.string.room_action_pin_failed),
    ARCHIVE(R.string.room_action_archive_failed),
    READ(R.string.room_action_read_failed),
    LEAVE(R.string.room_action_leave_failed),
}

/**
 * Список разговоров, который держит живым цикл синхронизации SDK. Порт `RoomListStore.swift`
 * (docs/PROMPT.md, 4.1 и 12.2).
 *
 * SDK присылает упорядоченный список комнат потоком диффов (вставить на 3, перенести вперёд…), а
 * не снимками. Стор применяет их к упорядоченному списку хендлов ([ListDiff][wtf.iskra.core.timeline.ListDiff])
 * и проецирует его в value-типы [RoomSummary], которые Compose сравнивает дёшево.
 *
 * **Поток.** Всё состояние стора живёт на одном потоке — главном ([scope]). Колбэки SDK приходят на
 * потоке Rust и переносятся туда сразу; диффы — через один упорядоченный канал, а не по корутине на
 * колбэк: дифф позиционный, и две пачки, применённые не в том порядке, оставляют список тихо
 * перемешанным (на iOS это поймали в ленте).
 *
 * Пока здесь не перенесено: подглядывание строк (`RoomPeek`), локальные имена,
 * перезапуск очередей отправки с паузой и слежение за сетью устройства — следующие пункты этапа 3.
 */
class RoomListStore(
    private val client: Client,
    private val strings: Strings,
    /** Поток стора. На устройстве — главный; JVM-тесты стора не поднимают (нужен клиент). */
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(RoomListState())
    val state: StateFlow<RoomListState> = mutableState.asStateFlow()

    /** Кто мы — для строк, которые описывают комнату тем, кто ещё в ней. Не меняется у клиента. */
    private val ownUserId: String? = runCatching { client.userId() }.getOrNull()

    private var syncService: SyncService? = null

    /**
     * Цикл синхронизации сеанса, если поднят, — для клиента уведомлений в том же процессе
     * (`NotificationProcessSetup.SingleProcess`): расшифровка пуша и цикл берут одно разрешение на
     * шифрованную синхронизацию, а не две, бок о бок, над одним стором.
     */
    internal val runningSyncService: SyncService? get() = syncService
    private var entriesResult: RoomListEntriesWithDynamicAdaptersResult? = null

    /**
     * То, что на самом деле держит поток диффов живым. Хендл потока — `entriesStream()`; на iOS его
     * долго никто не брал, и `stop()` гасил синхронизацию, оставляя диффы идти — в стор, который
     * выход из аккаунта вот-вот удалит с диска.
     */
    private var entriesHandle: TaskHandle? = null
    private var diffs: Channel<List<RoomListEntriesUpdate>>? = null
    private var diffsJob: Job? = null

    private var syncStateHandle: TaskHandle? = null
    private var syncIndicatorHandle: TaskHandle? = null
    private var roomListStateHandle: TaskHandle? = null
    private var sendQueueHandle: TaskHandle? = null
    private var sendQueueErrorHandle: TaskHandle? = null

    private var notificationSettings: NotificationSettings? = null
    private var mutedRoomIds: Set<String> = emptySet()

    /**
     * Хендлы комнат в порядке SDK — каждый со своим ID, прочитанным один раз, когда хендл пришёл:
     * `id()` — вызов в Rust и строка обратно, а пересборка спрашивала его по нескольку раз на комнату.
     */
    private val orderedRooms = mutableListOf<Listed>()

    private val mutableTyping = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Комнаты, где сейчас пишет кто-то, кроме тебя, — список говорит это вместо последнего сообщения.
     * Набором, а не полем `RoomSummary`: «печатает» приходит и уходит десяток раз в минуту, и связать
     * его со строками значило бы пересобирать весь список на каждое нажатие клавиши на том конце.
     */
    val typingRoomIds: StateFlow<Set<String>> = mutableTyping.asStateFlow()

    /** По подписке на комнату списка. Сами уведомления приходят с синхронизацией в любом случае. */
    private val typingHandles = mutableMapOf<String, TaskHandle>()

    private class Listed(val room: Room) {
        val id: String = room.id()
    }

    /** Строки, как собраны в последний раз, по ID; null — комнате не место в списке. */
    private val summaryCache = HashMap<String, RoomSummary?>()

    /** Неотправленные слова каждого чата: из стора SDK при первой сборке строки, потом — [noteDraft]. */
    private val drafts = HashMap<String, String?>()

    /** Докуда твои сообщения известны прочитанными — по времени отправки; сообщает открытый чат. */
    private val readUpTo = HashMap<String, Instant>()

    private val rebuilds = RebuildScheduler(scope, DEBOUNCE) { changed -> rebuildSummaries(changed) }

    /** Выставлен [stop]. Дифф, уже бывший в пути из Rust, приходит *после* остановки — и не трогается. */
    private var hasStopped = false

    /** Какой прогон стора. [stop] увеличивает; всё, что проснулось после await, сверяет. */
    private var generation = 0

    /** Синхронизация опущена нарочно (приложение в фоне) — не путать со сбоем, который надо чинить. */
    private var isPaused = false
    private val transitions = Mutex()
    private var isSyncAsleep = false
    private var isRestartingSync = false
    private var isResyncing = false

    private var sdkSaysUpdating = false
    private var isAwaitingFirstAnswer = false
    private var roomListAnswered = false
    private var firstAnswerWatch: Job? = null

    // MARK: Запуск

    /**
     * Запускает синхронизацию и поток комнат. Можно снова после [stop].
     *
     * Каждый await ниже — момент, в который может пройти [stop] (выход, смена аккаунта), а [stop]
     * разбирает только то, что уже есть. Поэтому каждый шаг спрашивает, нужен ли он ещё: иначе в
     * конце оказался бы цикл синхронизации закрытого сеанса, которого никто никогда не остановит.
     */
    suspend fun start() {
        if (syncService != null) return
        hasStopped = false
        val started = generation
        try {
            startSteps(started)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "список не поднялся: ${Log.describe(error)}")
            // Полусобранный и упавший: разобрать, чтобы следующий start() мог попробовать снова, а не
            // нашёл syncService и не вернулся навсегда, ничего не синхронизировав.
            if (started == generation) stop()
            throw error
        }
    }

    private fun isStillWanted(started: Int) = !hasStopped && started == generation

    private suspend fun startSteps(started: Int) {
        // Какие чаты заглушены — *рядом* с открытием, а не перед ним: это чтение правил и вопрос на
        // каждую комнату со своим правилом, и всё ради цвета значка. Список появляется сразу, а
        // несколько беззвучных значков сереют мгновением позже.
        scope.launch {
            watchMuting()
            if (isStillWanted(started)) rebuilds.schedule()
        }

        // Офлайн-режим — собственный ответ SDK на пропавшую сеть: без него цикл называет её ошибкой
        // и встаёт. С ним он говорит `Offline` и остаётся поднятым, читая из стора.
        val service = withTimeLimit(SDK_LIMIT) { client.syncService().withOfflineMode().withSharePos(true).finish() }
        if (!isStillWanted(started)) {
            service.close()
            return
        }
        syncService = service
        val listService = service.roomListService()

        // «Обновление…», пока список догоняет: сигнал SDK (тот же, что у Element X), показанный
        // через полсекунды, чтобы догонялка короче этого не мигала словом.
        syncIndicatorHandle = listService.syncIndicator(
            UPDATING_SHOWS_AFTER_MS.toUInt(),
            0u,
            object : RoomListServiceSyncIndicatorListener {
                override fun onUpdate(syncIndicator: RoomListServiceSyncIndicator) {
                    scope.launch { syncIndicatorChanged(syncIndicator == RoomListServiceSyncIndicator.SHOW) }
                }
            },
        )

        val list = withTimeLimit(SDK_LIMIT) { listService.allRooms() }
        if (!isStillWanted(started)) return

        // Одна упорядоченная очередь из потока Rust в поток стора — см. описание класса.
        val channel = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)
        diffs = channel
        diffsJob = scope.launch {
            for (batch in channel) apply(batch)
        }
        val listener = object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                channel.trySend(roomEntriesUpdate)
            }
        }

        val result = list.entriesWithDynamicAdapters(PAGE_SIZE.toUInt(), listener)
        if (!isStillWanted(started)) {
            result.entriesStream().cancel()
            channel.close()
            return
        }
        entriesResult = result
        entriesHandle = result.entriesStream()
        // Без фильтра покинутая комната оставалась в списке: её можно было открыть и читать, и
        // выглядело так, будто «Выйти» не сработало. `nonLeft` — имя SDK для «комнаты, в которых ты
        // есть»; неотвеченное приглашение — тоже не покинутая, так что спрятано ничего не решённого.
        result.controller().setFilter(FILTER)

        // Второй источник правды. Список комнат шлёт дифф, когда что-то изменил *сервер*; только что
        // написанное лежит в очереди отправки, и без этого строка показывала бы последнее *дошедшее*,
        // а отправленное (или не ушедшее) не было бы видно нигде. Только та комната, о которой новость.
        val queueHandle = withTimeLimit(SDK_LIMIT) {
            client.subscribeToSendQueueUpdates(object : SendQueueRoomUpdateListener {
                override fun onUpdate(roomId: String, update: RoomSendQueueUpdate) {
                    scope.launch { if (!hasStopped) rebuilds.schedule(setOf(roomId)) }
                }
            })
        }
        if (!isStillWanted(started)) {
            queueHandle.cancel()
            return
        }
        sendQueueHandle = queueHandle

        // Очередь, остановившая себя на сбое, который может пройти. Пока — сразу вслед за возвратом
        // сети (`syncStateChanged`); перезапуск с паузой (`SendQueueRecovery`) придёт отдельным пунктом.
        sendQueueErrorHandle = client.subscribeToSendQueueStatus(object : SendQueueRoomErrorListener {
            override fun onError(roomId: String, error: ClientException) {
                Log.info(Log.Category.ROOMS, "очередь отправки остановилась: ${Log.describe(error)}")
            }
        })

        // Никто не слушал это на iOS, а цикл синхронизации — единственное, чему нельзя падать молча:
        // он останавливается на сетевой ошибке и остаётся стоять, список замерзает на последнем, и
        // ничто на экране этого не говорит. Лечилось только убийством приложения.
        syncStateHandle = service.state(object : SyncServiceStateObserver {
            override fun onUpdate(state: SyncServiceState) {
                scope.launch { syncStateChanged(state) }
            }
        })

        roomListStateHandle = listService.state(object : RoomListServiceStateListener {
            override fun onUpdate(state: RoomListServiceState) {
                scope.launch {
                    if (hasStopped) return@launch
                    roomListAnswered = FirstAnswerWatch.answered(state)
                    watchFirstAnswer()
                }
            }
        })

        // Тот, что важен: цикл, запущенный после stop(), — зомби.
        if (!isStillWanted(started)) return
        isPaused = false
        isSyncAsleep = false
        service.start()
        watchFirstAnswer()
    }

    // MARK: Пауза

    /**
     * Приложение ушло в фон. Синхронизация останавливается: пока приложения не видно, сообщения
     * приносят пуши (этап 7), а живой цикл держал бы стор открытым ровно тогда, когда его открывает
     * обработчик пуша.
     */
    suspend fun pause() {
        if (hasStopped || syncService == null) return
        isPaused = true
        watchFirstAnswer()
        settleSync()
    }

    /** И обратно. `start()` у сервиса, не у стора: слушатели, потоки и хендлы никто не разбирал. */
    suspend fun resume() {
        if (hasStopped || !isPaused || syncService == null) return
        isPaused = false
        settleSync()
        watchFirstAnswer()
    }

    /**
     * Приводит цикл к тому, что говорит [isPaused] *сейчас*, после всех прежних переходов. На iOS
     * карман-и-обратно запускал паузу и возобновление бок о бок: start() возвращался первым, stop()
     * ложился после, и цикл оставался стоять с `isPaused == false` — и никто его больше не будил.
     */
    private suspend fun settleSync() = transitions.withLock {
        val service = syncService ?: return@withLock
        if (hasStopped || isSyncAsleep == isPaused) return@withLock
        val asleep = isPaused
        isSyncAsleep = asleep
        if (asleep) service.stop() else service.start()
    }

    // MARK: Состояние синхронизации

    private fun syncIndicatorChanged(isUpdating: Boolean) {
        if (hasStopped) return
        sdkSaysUpdating = isUpdating
        refreshUpdating()
    }

    private fun refreshUpdating() {
        val updating = sdkSaysUpdating || isAwaitingFirstAnswer
        val was = mutableState.value.isUpdating
        mutableState.update { it.copy(isUpdating = updating) }
        // Догонялка кончилась: один полный проход как сетка под частичными пересборками — что-то,
        // чего дифф не назвал, а долгий синк изменил, подберётся здесь.
        if (was && !updating) rebuilds.schedule()
    }

    /**
     * Ждёт первого ответа сервера с момента, как синхронизация поднялась. Индикатор SDK молчит как
     * раз тогда, когда нужнее всего: после возвращения из фона список `Terminated`, потом
     * `Recovering`, и оба *скрыты*, а состояние SDK записывает только после ответа. Поэтому до ответа
     * заголовок говорит «Обновление…», а после [FirstAnswerWatch.stalledAfter] — «Ожидание сети».
     */
    private fun watchFirstAnswer() {
        val waiting = FirstAnswerWatch.isWaiting(
            stopped = hasStopped || syncService == null,
            paused = isPaused,
            offline = mutableState.value.isOffline,
            answered = roomListAnswered,
        )
        if (!waiting) {
            firstAnswerWatch?.cancel()
            firstAnswerWatch = null
            mutableState.update { it.copy(isStalled = false) }
            if (isAwaitingFirstAnswer) {
                isAwaitingFirstAnswer = false
                refreshUpdating()
            }
            return
        }
        if (firstAnswerWatch != null) return

        val mine = generation
        firstAnswerWatch = scope.launch {
            delay(FirstAnswerWatch.updatingAfter)
            if (mine != generation) return@launch
            isAwaitingFirstAnswer = true
            refreshUpdating()

            delay(FirstAnswerWatch.stalledAfter - FirstAnswerWatch.updatingAfter)
            if (mine != generation) return@launch
            Log.error(Log.Category.SESSION, "сервер молчит с тех пор, как синхронизация поднялась; так и говорим")
            mutableState.update { it.copy(isStalled = true) }
        }
    }

    /**
     * Цикл остановился, и никто его не просил. Запустить снова — после паузы: обычная причина —
     * пропавшая сеть, и перезапуск в ту же пустоту — ошибка, которая рождает перезапуск. Фиксированная
     * пауза превращает горячую петлю в медленную; повторы ниже SDK делает сам.
     */
    private suspend fun syncStateChanged(state: SyncServiceState) {
        if (hasStopped) return
        // Единственное место, где приложение что-то узнаёт о сети, — и лучший факт, чем интерфейс:
        // интерфейс поднят и за captive portal, и в VPN без маршрута. Это — доходим ли мы до сервера.
        val offline = state == SyncServiceState.OFFLINE
        mutableState.update { it.copy(isOffline = offline) }

        // Всё, от чего очередь отправки не избавилась без сети. SDK останавливает очередь комнаты на
        // сбое и сам не запускает: без этого написанное офлайн лежало бы и после возвращения сети.
        if (state == SyncServiceState.RUNNING) {
            runCatching { withTimeLimit(SDK_LIMIT) { client.enableAllSendQueues(true) } }
        }

        watchFirstAnswer()

        if (state != SyncServiceState.ERROR && state != SyncServiceState.TERMINATED) return
        if (isPaused || isRestartingSync) return

        isRestartingSync = true
        try {
            Log.error(Log.Category.SESSION, "цикл синхронизации остановился сам; запускаем снова")
            delay(SYNC_RESTART_DELAY)
            // Пока ждали, могли выйти из аккаунта или убрать приложение в фон — оба опустили цикл нарочно.
            if (hasStopped || isPaused) return
            syncService?.start()
        } finally {
            isRestartingSync = false
        }
    }

    // MARK: Остановка

    /**
     * Гасит список — и всерьёз. Хендл потока отменяется, всё отпускается, а [hasStopped] ловит то,
     * что уже было в пути: отменённый хендл останавливает *новые* диффы, но не отзывает тот, что уже
     * пересёк границу FFI. Выход из аккаунта после этого удаляет стор с диска.
     */
    suspend fun stop() {
        hasStopped = true
        generation += 1
        rebuilds.cancel()

        entriesHandle?.cancel()
        entriesHandle = null
        diffs?.close()
        diffs = null
        diffsJob?.cancel()
        diffsJob = null

        typingHandles.values.forEach { it.cancel() }
        typingHandles.clear()
        mutableTyping.value = emptySet()

        sendQueueHandle?.cancel()
        sendQueueHandle = null
        sendQueueErrorHandle?.cancel()
        sendQueueErrorHandle = null

        // До остановки сервиса, не после: остановка переведёт его в состояние, за которым следит
        // наблюдатель, и ещё слушающий наблюдатель принял бы нарочную остановку за сбой и поднял всё снова.
        syncStateHandle?.cancel()
        syncStateHandle = null
        syncIndicatorHandle?.cancel()
        syncIndicatorHandle = null
        roomListStateHandle?.cancel()
        roomListStateHandle = null
        firstAnswerWatch?.cancel()
        firstAnswerWatch = null
        roomListAnswered = false
        sdkSaysUpdating = false
        isAwaitingFirstAnswer = false

        // Ограничено: это путь из аккаунта, и выход не должен зависеть от здоровья сети.
        syncService?.let { service ->
            runCatching { withTimeLimit(STOP_LIMIT) { service.stop() } }
            service.close()
        }
        syncService = null
        entriesResult?.close()
        entriesResult = null

        // Хендлы — нативные объекты: GC о памяти за ними не знает (docs/PROMPT.md, 4.1).
        orderedRooms.forEach { it.room.close() }
        orderedRooms.clear()

        notificationSettings?.let { settings ->
            runCatching { settings.setDelegate(null) }
            settings.close()
        }
        notificationSettings = null
        mutedRoomIds = emptySet()

        // И экран забывает разговоры: список чужих чатов не остаётся на экране вышедшего приложения
        // оттого, что дерево экранов случайно разобралось первым. Следующий аккаунт начинает с загрузки.
        summaryCache.clear()
        drafts.clear()
        readUpTo.clear()
        mutableState.value = RoomListState()
    }

    // MARK: Диффы

    private fun apply(batch: List<RoomListEntriesUpdate>) {
        // Уже был в пути, когда остановились: трогать хендлы, чей стор, возможно, только что удалён.
        if (hasStopped) return

        // Комнаты, о которых эта пачка что-то говорит, — только их строки и спрашивать снова.
        val touched = mutableSetOf<String>()
        val before = orderedRooms.toList()
        val outcome = orderedRooms.applyDiffs(
            batch.map { update ->
                update.asListDiff().map { room -> Listed(room).also { touched += it.id } }
            },
        )
        closeDropped(before)
        watchTyping()

        when (outcome) {
            DiffOutcome.Applied -> rebuilds.schedule(touched)
            is DiffOutcome.Desynced -> desynced(outcome.what)
        }
    }

    /** Новые комнаты списка — слушать, ушедшие — отпустить. */
    private fun watchTyping() {
        val current = orderedRooms.map { it.id }.toSet()
        for (listed in orderedRooms) {
            val roomId = listed.id
            if (roomId in typingHandles) continue
            typingHandles[roomId] = runCatching {
                listed.room.subscribeToTypingNotifications(object : TypingNotificationsListener {
                    override fun call(typingUserIds: List<String>) {
                        // Сервер возвращает и наше собственное уведомление — это не новость.
                        val others = typingUserIds.any { it != ownUserId }
                        scope.launch {
                            if (hasStopped) return@launch
                            mutableTyping.value = if (others) mutableTyping.value + roomId else mutableTyping.value - roomId
                        }
                    }
                })
            }.getOrNull() ?: continue
        }
        val gone = typingHandles.keys - current
        for (roomId in gone) typingHandles.remove(roomId)?.cancel()
        if (gone.isNotEmpty()) mutableTyping.value = mutableTyping.value - gone
    }

    /**
     * Закрывает хендлы, которых в списке больше нет. Каждый дифф несёт *новый* хендл, даже для той же
     * комнаты, так что заменённый `set`ом или снятый — свободен; пересборка, которая успела его взять,
     * получит ошибку и пропустит комнату — комнаты в списке уже и нет.
     */
    private fun closeDropped(before: List<Listed>) {
        if (before.isEmpty()) return
        val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Listed, Boolean>())
        kept.addAll(orderedRooms)
        before.forEach { if (it !in kept) it.room.close() }
    }

    /**
     * Список больше не тот, что описывает SDK, — попросить его заново. Повторная установка фильтра
     * заставляет адаптер переслать записи `reset`ом — собственным отчётом SDK о списке, то есть ровно
     * тем, что мы потеряли. Синхронизацию не трогаем, экран не опустошаем.
     */
    private fun desynced(what: String) {
        val result = entriesResult ?: return
        if (isResyncing) return
        isResyncing = true
        Log.error(Log.Category.ROOMS, "список комнат разошёлся с SDK: $what; просим заново")
        result.controller().setFilter(FILTER)
        isResyncing = false
    }

    // MARK: Пересборка

    /** Что SDK сказал об одной комнате — всё, что нужно строке и чего у стора ещё нет. */
    private class Answer(val info: RoomInfo, val latest: LatestEventValue, val draft: Result<String?>?)

    /**
     * [changed] — комнаты, о которых спросить SDK снова; остальные берут строку из кэша. Null — всех.
     *
     * Первые строки появляются, как только их набирается на экран ([FIRST_PAINT]), а не после
     * последней из нескольких сотен: на iOS это ожидание и было «Загрузка сообщений…» на каждом старте.
     */
    private suspend fun rebuildSummaries(changed: Set<String>?) {
        val listed = orderedRooms.toList()
        val mine = generation
        fun isCurrent() = !hasStopped && mine == generation

        val summaries = ArrayList<RoomSummary>(listed.size)
        var isFirstPaint = mutableState.value.rooms.isEmpty() && mutableState.value.archivedRooms.isEmpty()

        // Пачкой, и пачку спрашивают разом: SDK отвечает параллельно, порядок держит сборка по порядку.
        for (batch in listed.chunked(ASKED_AT_ONCE)) {
            if (!isCurrent()) return
            val toAsk = batch.filter { changed == null || it.id in changed || it.id !in summaryCache }
            val answers = if (toAsk.isEmpty()) emptyMap() else ask(toAsk)
            if (!isCurrent()) return

            for (entry in batch) {
                if (entry !in toAsk) {
                    summaryCache[entry.id]?.let(summaries::add)
                    continue
                }
                val summary = answers[entry.id]?.let { summary(entry.id, it) }
                summaryCache[entry.id] = summary
                summary?.let(summaries::add)
                // Ответ SDK — нативные объекты внутри `RoomInfo`; строка уже собрана, они не нужны.
                answers[entry.id]?.info?.destroy()
            }

            if (isFirstPaint && summaries.size >= FIRST_PAINT) {
                isFirstPaint = false
                publish(summaries)
            }
        }

        if (!isCurrent()) return
        publish(summaries)

        // Ушедшие из списка комнаты забирают свои строки с собой.
        val present = listed.mapTo(HashSet()) { it.id }
        summaryCache.keys.retainAll(present)
    }

    /**
     * Спрашивает обо всех комнатах разом, вне главного потока, и отдаёт, что сказала каждая.
     * Комната, чей `roomInfo()` не ответил (или чей хендл уже закрыт), просто отсутствует в ответе.
     */
    private suspend fun ask(entries: List<Listed>): Map<String, Answer> {
        // Решено здесь, на потоке стора: `drafts` — его состояние, и с IO его не читают.
        val wantDrafts = entries.filterTo(HashSet()) { !drafts.containsKey(it.id) }
        return withContext(Dispatchers.IO) { askAll(entries, wantDrafts) }
    }

    private suspend fun askAll(entries: List<Listed>, wantDrafts: Set<Listed>): Map<String, Answer> =
        coroutineScope {
            entries.map { entry ->
                val wantsDraft = entry in wantDrafts
                async {
                    try {
                        withTimeLimit(ROOM_LIMIT) {
                            val info = entry.room.roomInfo()
                            val latest = entry.room.latestEvent()
                            val draft = if (wantsDraft) {
                                runCatching {
                                    entry.room.loadComposerDraft(null)?.plainText?.trim()?.takeIf { it.isNotEmpty() }
                                }
                            } else {
                                null
                            }
                            entry.id to Answer(info, latest, draft)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

    private fun summary(roomId: String, answer: Answer): RoomSummary? {
        // Черновик, отмеченный, пока стор читали, новее того, что сказал стор.
        answer.draft?.getOrNull().let { saved ->
            if (answer.draft != null && !drafts.containsKey(roomId)) drafts[roomId] = saved
        }
        val last = MessagePreview.lastMessage(answer.latest, strings, readUpTo[roomId])
        return RoomSummary.of(
            info = answer.info,
            ownUserId = ownUserId,
            last = last,
            isMuted = roomId in mutedRoomIds,
            draft = drafts[roomId],
            strings = strings,
        )
    }

    private fun publish(summaries: List<RoomSummary>) {
        val (active, archived) = RoomOrdering.partitionArchived(summaries)
        mutableState.update {
            it.copy(
                rooms = RoomOrdering.byRecency(active),
                archivedRooms = RoomOrdering.byRecency(archived),
                isLoadingInitialRooms = false,
            )
        }
    }

    // MARK: Заглушённые

    /**
     * Читает заглушённые чаты и подписывается на перемены. Заглушение пишет *правило уведомлений*, не
     * состояние комнаты, — дифф о нём не придёт никогда, и без подписки значок оставался в акценте до
     * первого постороннего сообщения. Сбои глотаются: цена ошибки — значок не того цвета, а цена
     * паники — список, который не появился.
     */
    private suspend fun watchMuting() {
        val settings = runCatching { withTimeLimit(SDK_LIMIT) { client.getNotificationSettings() } }.getOrNull() ?: return
        if (hasStopped) {
            settings.close()
            return
        }
        notificationSettings = settings
        settings.setDelegate(object : NotificationSettingsDelegate {
            override fun settingsDidChange() {
                scope.launch {
                    reloadMuting()
                    if (!hasStopped) rebuilds.schedule()
                }
            }
        })
        reloadMuting()
    }

    private suspend fun reloadMuting() {
        val settings = notificationSettings ?: return
        mutedRoomIds = runCatching {
            withTimeLimit(SDK_LIMIT) {
                // `null`, а не `true`: аргумент спрашивает, *включено* ли правило, а не что оно говорит.
                settings.getRoomsWithUserDefinedRules(null).filterTo(HashSet()) { roomId ->
                    runCatching { settings.getUserDefinedRoomNotificationMode(roomId) }.getOrNull() == RoomNotificationMode.MUTE
                }
            }
        }.getOrDefault(mutedRoomIds)
    }

    // MARK: Что сообщают другие экраны

    /** Черновик чата сохранён или стёрт — см. [RoomSummary.draft]. */
    fun noteDraft(roomId: String, text: String?) {
        if (drafts.containsKey(roomId) && drafts[roomId] == text) return
        drafts[roomId] = text
        rebuilds.schedule(setOf(roomId))
    }

    /** Открытый чат увидел твои сообщения прочитанными до [date]. Строка пересобирается, если это новость. */
    fun noteRead(roomId: String, date: Instant) {
        val known = readUpTo[roomId]
        if (known != null && date <= known) return
        readUpTo[roomId] = date
        rebuilds.schedule(setOf(roomId))
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    // MARK: Приглашения и порядок

    /**
     * Принять приглашение. Список здесь не трогается: вступление меняет членство на сервере, сервер
     * говорит циклу, цикл шлёт дифф, и строка перерисовывается чатом — той же дорогой, что любая
     * перемена, и единственной, которая не может разойтись с правдой.
     */
    suspend fun accept(roomId: String) {
        try {
            withTimeLimit(ACTION_LIMIT) { client.joinRoomById(roomId).close() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "приглашение не принялось: ${Log.describe(error)}")
            fail(RoomActionFailure.ACCEPT_INVITE)
        }
    }

    /** Отклонить — это `leave()`: «отказа» в Matrix нет, уход из комнаты, куда позвали, и *есть* отказ. */
    suspend fun decline(roomId: String) = act(roomId, RoomActionFailure.DECLINE_INVITE) { it.leave() }

    /** Закрепить (`m.favourite`): закрепление едет с аккаунтом, а не с устройством. */
    suspend fun setPinned(roomId: String, pinned: Boolean) =
        act(roomId, RoomActionFailure.PIN) { it.setIsFavourite(pinned, null) }

    /** В архив (`m.lowpriority`). История нетронута — комната просто вне главного списка. */
    suspend fun setArchived(roomId: String, archived: Boolean) =
        act(roomId, RoomActionFailure.ARCHIVE) { it.setIsLowPriority(archived, null) }

    /**
     * Прочитано — и обе половины руками: `markAsRead` шлёт квитанцию, а документация SDK прямо
     * говорит, что флаг «непрочитано» он *не* снимает. Без второй половины значок оставался.
     */
    suspend fun markRead(roomId: String) = act(roomId, RoomActionFailure.READ) {
        it.markAsRead(ReceiptType.READ)
        it.setUnreadFlag(false)
    }

    suspend fun markUnread(roomId: String) = act(roomId, RoomActionFailure.READ) { it.setUnreadFlag(true) }

    /** Выйти — «удалить чат». Удаления в Matrix нет: уходишь, комната не проходит `nonLeft` и уходит из списка сама. */
    suspend fun leave(roomId: String) = act(roomId, RoomActionFailure.LEAVE) { it.leave() }

    private suspend fun act(roomId: String, failure: RoomActionFailure, body: suspend (Room) -> Unit) {
        val room = orderedRooms.firstOrNull { it.id == roomId }?.room
        if (room == null) {
            fail(failure)
            return
        }
        try {
            withTimeLimit(ACTION_LIMIT) { body(room) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "действие ${failure.name.lowercase()} не удалось: ${Log.describe(error)}")
            fail(failure)
        }
    }

    private fun fail(failure: RoomActionFailure) = mutableState.update { it.copy(failure = failure) }

    /** Приглашена ли ещё комната — для экрана приглашения, без хендла наружу. */
    internal fun membership(roomId: String): Membership? =
        orderedRooms.firstOrNull { it.id == roomId }?.room?.let { runCatching { it.membership() }.getOrNull() }

    private companion object {
        /** Каждая комната, из которой ты не ушёл. Одно имя: ставится дважды, и два написания разошлись бы. */
        val FILTER: RoomListEntriesDynamicFilterKind =
            RoomListEntriesDynamicFilterKind.All(listOf(RoomListEntriesDynamicFilterKind.NonLeft))

        /** Сколько дать пачке диффов улечься перед пересборкой (docs/PROMPT.md, 4.1). */
        val DEBOUNCE = 50.milliseconds

        /** Сколько строк на экран до остальных — экран и ещё немного. */
        const val FIRST_PAINT = 30

        /** Сколько комнат спрашивать разом: не очередь, но и не сотни задач на один стор. */
        const val ASKED_AT_ONCE = 16

        const val PAGE_SIZE = 100
        const val UPDATING_SHOWS_AFTER_MS = 500

        val SDK_LIMIT = 30.seconds
        val ROOM_LIMIT = 10.seconds
        val ACTION_LIMIT = 30.seconds

        /** Коротко: ответа никто не ждёт, ждут выхода, которому это мешает. */
        val STOP_LIMIT = 5.seconds
        val SYNC_RESTART_DELAY = 2.seconds
    }
}
