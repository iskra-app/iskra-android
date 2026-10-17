package moscow.swag.iskra.core.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moscow.swag.iskra.core.R
import moscow.swag.iskra.core.rooms.PrivacyStatus
import moscow.swag.iskra.core.rooms.RoomFace
import moscow.swag.iskra.core.rooms.RoomKind
import moscow.swag.iskra.core.rooms.avatarSeed
import moscow.swag.iskra.core.rooms.faces
import moscow.swag.iskra.core.rooms.kind
import moscow.swag.iskra.core.rooms.of
import moscow.swag.iskra.core.rooms.pictureUrl
import moscow.swag.iskra.core.rooms.title
import moscow.swag.iskra.core.support.Log
import moscow.swag.iskra.core.support.Strings
import moscow.swag.iskra.core.support.withTimeLimit
import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.QueueWedgeError
import org.matrix.rustcomponents.sdk.SendHandle
import org.matrix.rustcomponents.sdk.MessageLikeEventType
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import kotlin.time.Duration.Companion.seconds

/** Шапка переписки — то, что ей нужно, и не больше. Остальное — экран «О чате». */
data class RoomHeader(
    val name: String = "",
    val avatarUrl: String? = null,
    /** Null, пока нет сведений о комнате: пустой сид — тоже строка, и существо сменилось бы при загрузке. */
    val avatarSeed: String? = null,
    val faces: List<RoomFace> = emptyList(),
    val kind: RoomKind = RoomKind.GROUP,
    /** Ровно один собеседник *сейчас*: имя и лицо над каждым его сообщением не нужны. */
    val isOneToOne: Boolean = false,
    val memberCount: Int = 0,
    val privacy: PrivacyStatus = PrivacyStatus.UNKNOWN,
)

/** Что лента показывает. Снимок для экрана. */
data class TimelineState(
    val rows: List<TimelineRow> = emptyList(),
    val header: RoomHeader = RoomHeader(),
    /** Крутится ли страница истории, о которой попросил *сам читающий*. */
    val isPaginating: Boolean = false,
    /** False, когда долистали до начала переписки. */
    val canLoadMore: Boolean = true,
    /** SDK сказал, что в комнате, — *включая* «ничего». Пустая комната и не пришедшая — не одно и то же. */
    val hasLoaded: Boolean = false,
    /** Комната не открылась. Не то же, что пустая, и только с этим человек может что-то сделать. */
    val didFailToLoad: Boolean = false,
    /** Последняя страница не пришла — история есть, но достать её не вышло (офлайн — обычное дело). */
    val didFailToPaginate: Boolean = false,
    /** Можно ли писать сюда. С начала true: пока права не пришли, безопасно держать композер на экране. */
    val canPost: Boolean = true,
    /** Кто печатает, по имени в этой комнате. Себя нет никогда. */
    val typing: List<String> = emptyList(),
    val failure: TimelineFailure? = null,
) {
    /** Есть ещё история, и ничто на экране не может её попросить — комнату из двух сообщений не прокрутить. */
    val needsMoreHistory: Boolean get() = hasLoaded && canLoadMore && !isPaginating && !didFailToPaginate && rows.size < SCREENFUL

    /** История есть, а достать её нельзя: строка наверху говорит правду вместо кнопки, которая не сработает. */
    val historyIsStranded: Boolean get() = hasLoaded && canLoadMore && didFailToPaginate

    internal companion object {
        /** Примерно экран телефона: дальше можно прокручивать, а прокрутка просит остальное. */
        const val SCREENFUL = 15
    }
}

/** Что человек попросил у комнаты, и это не случилось. */
enum class TimelineFailure(val message: Int) {
    SEND(R.string.timeline_send_failed),
    REACTION(R.string.timeline_reaction_failed),
    DELETE(R.string.timeline_delete_failed),
    EDIT(R.string.timeline_edit_failed),
}

/**
 * Переписка одной комнаты: сообщения и возможность добавить к ним. Порт `TimelineStore.swift`
 * (docs/PROMPT.md, 4.2 и 12.3). Как список комнат, SDK шлёт её диффами, но среди элементов есть
 * локальное эхо — то, что сообщение появляется сразу и потом тихо получает галочку.
 *
 * Пока перенесено основное: открытие с явной первой страницей, пагинация, отправка и ответ, правка,
 * удаление, реакции, прочитанное, черновики, «печатает». Медиа, закреплённые, треды, пересылка,
 * присутствие и ожидающие отправки пузыри — следующими пунктами этапов 4 и 6.
 */
class TimelineStore internal constructor(
    private val room: Room,
    val roomId: String,
    private val ownUserId: String,
    private val strings: Strings,
    /** Поток стора — главный (см. `UserSession`). */
    private val scope: CoroutineScope,
    /** Черновик сохранён или стёрт — сказать списку чатов («Черновик: …»). */
    private val onDraftChanged: (String?) -> Unit = {},
    /** Свои сообщения прочитаны до этого времени — квитанции есть только в ленте, список их не видит. */
    private val onOwnMessagesRead: (java.time.Instant) -> Unit = {},
) {
    private val mutableState = MutableStateFlow(TimelineState())
    val state: StateFlow<TimelineState> = mutableState.asStateFlow()

    private var timeline: Timeline? = null
    private val items = mutableListOf<TimelineItem>()
    private val memo = TimelineMapper.Memo()

    /** Строки последней сборки — мера для [fillPages], даже когда экран держит старые (см. [refill]). */
    private var latestRows: List<TimelineRow> = emptyList()

    private var diffs: Channel<List<TimelineDiff>>? = null
    private var diffsJob: Job? = null
    private var listenerHandle: TaskHandle? = null
    private var roomInfoHandle: TaskHandle? = null
    private var typingHandle: TaskHandle? = null

    /**
     * Какое открытие. [stop] увеличивает; всё, что пережило await, сверяет перед записью: иначе
     * открытие, брошенное на полпути, писало бы в комнату, которая его сменила.
     */
    private var generation = 0
    private var opening: Deferred<Unit>? = null
    private var pageInFlight: Deferred<Boolean>? = null
    private var refillJob: Job? = null
    private var isRefilling = false
    private var isResyncing = false
    private val askedForReplyDetails = mutableSetOf<String>()
    private var reportedReadUpTo: java.time.Instant? = null

    /** Дошёл ли читающий до конца — это двигает полосу «Непрочитанные», но на *выходе*. */
    private var hasReadToEnd = false

    private var isTellingThemWeType = false
    private var typingStopper: Job? = null

    // MARK: Открытие

    suspend fun start() {
        opening?.let {
            it.await()
            return
        }
        if (timeline != null) return
        mutableState.update { it.copy(didFailToLoad = false, didFailToPaginate = false) }

        val mine = generation
        val task = scope.async {
            try {
                open()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Уход читающего во время открытия — не сбой открытия.
                if (mine != generation) return@async
                Log.error(Log.Category.TIMELINE, "комната не открылась: ${Log.describe(error)}")
                // Записано, а не проглочено: экран скажет «чат не открылся» и предложит ещё раз,
                // вместо пустой комнаты, что было бы ложью о переписке, которая может быть полной.
                mutableState.update { it.copy(didFailToLoad = true) }
                stop()
            }
        }
        opening = task
        try {
            task.await()
        } finally {
            if (mine == generation) opening = null
        }
    }

    suspend fun retryLoading() = start()

    private suspend fun open() {
        val mine = generation

        runCatching { withTimeLimit(SDK_LIMIT) { room.roomInfo() } }.getOrNull()?.let { info ->
            if (mine == generation) adopt(info) else info.destroy()
        }
        if (mine != generation) return

        // И держать в курсе: переименование, новое фото, кто-то вошёл.
        roomInfoHandle = room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                scope.launch { if (mine == generation) adopt(roomInfo) else roomInfo.destroy() }
            }
        })

        val configuration = TimelineConfiguration(
            focus = TimelineFocus.Live(hideThreadedEvents = false),
            filter = TimelineFilter.All,
            internalIdPrefix = null,
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.ALL_EVENTS,
            reportUtds = false,
        )
        val built = withTimeLimit(SDK_LIMIT) { room.timelineWithConfiguration(configuration) }
        if (mine != generation) {
            built.close()
            return
        }
        timeline = built

        // Диффы идут в поток стора *очередью*, и это не деталь: каждый дифф позиционный, и пачка
        // значит что-то только против списка, который дала предыдущая. По корутине на колбэк —
        // порядок не обещан, и переписка тихо перемешивается (на iOS так и было при пагинации).
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

        typingHandle = room.subscribeToTypingNotifications(object : TypingNotificationsListener {
            override fun call(typingUserIds: List<String>) {
                scope.launch { if (mine == generation) adoptTyping(typingUserIds) }
            }
        })

        // Живая лента начинается *пустой*: подписка не отдаёт переписку, а первый дифф свежей комнаты —
        // reset без элементов. Уже имеющееся *запрашивается*, и запрашивается здесь, явно, — а не
        // побочным эффектом спиннера, который кто-нибудь разумно удалит (docs/PROMPT.md, 4.2).
        fillPages(untilAtLeast = TimelineState.SCREENFUL, maximumPages = MAXIMUM_OPENING_PAGES)
        if (mine != generation) return

        // Первая страница свежей комнаты не пришла вовсе — это чат, который не открылся, а не пустой.
        if (state.value.didFailToPaginate && latestRows.isEmpty()) throw IllegalStateException("first page unavailable")

        // Только теперь: между пустым reset и первой страницей комната и правда пуста и правда не
        // загружена, и «сообщений пока нет» в этот промежуток было бы неправдой.
        mutableState.update { it.copy(hasLoaded = true) }
    }

    private fun adopt(info: RoomInfo) {
        val levels = info.powerLevels
        val people = info.joinedMembersCount.toLong() + info.invitedMembersCount.toLong()
        val oneToOne = info.isDirect && (if (people == 0L) info.isDm else people <= 2)
        mutableState.update { state ->
            state.copy(
                header = RoomHeader(
                    name = info.title(ownUserId, strings),
                    avatarUrl = info.pictureUrl(ownUserId),
                    avatarSeed = info.avatarSeed(ownUserId),
                    faces = if (info.kind == RoomKind.GROUP) info.faces(ownUserId) else emptyList(),
                    kind = info.kind,
                    isOneToOne = oneToOne,
                    memberCount = info.joinedMembersCount.toInt(),
                    privacy = PrivacyStatus.of(info.encryptionState),
                ),
                // Права приходят не всегда; «нет» — только на ясное слово комнаты.
                canPost = levels?.let { runCatching { it.canOwnUserSendMessage(MessageLikeEventType.RoomMessage) }.getOrNull() } ?: state.canPost,
            )
        }
        info.destroy()
    }

    /**
     * Просит историю, пока её не станет достаточно для прокрутки. Повторно: страница — 25 *событий*,
     * а не сообщений, и комната может ответить двадцатью пятью вступлениями. А прокрутка — то, что
     * просит следующую страницу: слишком короткую переписку не прокрутить, и она застряла бы.
     */
    private suspend fun fillPages(untilAtLeast: Int, maximumPages: Int) {
        var fetched = 0
        var waited = 0
        val mine = generation
        while (mine == generation && latestRows.size < untilAtLeast && state.value.canLoadMore &&
            fetched < maximumPages && waited < maximumPages
        ) {
            when (page(showingSpinner = !isRefilling)) {
                PageOutcome.FETCHED -> fetched += 1
                PageOutcome.WAITED -> waited += 1
                // Сразу снова — снова неудача; останавливаемся и оставляем нехватку *видимой*.
                PageOutcome.FAILED -> return
            }
        }
    }

    /** Кнопка «показать раньше»: просит экран, а не страницу. Нажатие — и есть разрешение попробовать снова. */
    suspend fun loadMoreHistory() {
        mutableState.update { it.copy(didFailToPaginate = false) }
        fillPages(state.value.rows.size + TimelineState.SCREENFUL, MAXIMUM_OPENING_PAGES)
    }

    /** Ещё истории — при прокрутке к началу. Одна неудача останавливает *автоматические* просьбы. */
    suspend fun loadMore() {
        if (state.value.didFailToPaginate) return
        page(showingSpinner = !isRefilling)
    }

    private enum class PageOutcome { FETCHED, WAITED, FAILED }

    /**
     * Одна страница истории, и никогда две разом: ждущий чужую страницу ждёт её, а не уходит ни с
     * чем — иначе дозаполнение тратило бы бюджет на ожидание и публиковало пустую комнату.
     */
    private suspend fun page(showingSpinner: Boolean): PageOutcome {
        pageInFlight?.let { inFlight ->
            inFlight.await()
            if (pageInFlight === inFlight) pageInFlight = null
            return PageOutcome.WAITED
        }
        val timeline = timeline ?: return PageOutcome.FAILED
        if (!state.value.canLoadMore) return PageOutcome.WAITED
        if (showingSpinner) mutableState.update { it.copy(isPaginating = true) }

        val mine = generation
        val task = scope.async {
            val reachedStart = try {
                withTimeLimit(SDK_LIMIT) { timeline.paginateBackwards(PAGE_SIZE.toUShort()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.info(Log.Category.TIMELINE, "страница истории не пришла: ${Log.describe(error)}")
                return@async false
            }
            if (mine == generation) mutableState.update { it.copy(canLoadMore = !reachedStart) }
            true
        }
        pageInFlight = task
        val didFetch = task.await()
        if (mine != generation) {
            if (pageInFlight === task) pageInFlight = null
            return PageOutcome.FETCHED
        }
        pageInFlight = null
        mutableState.update { it.copy(isPaginating = false, didFailToPaginate = !didFetch) }
        return if (didFetch) PageOutcome.FETCHED else PageOutcome.FAILED
    }

    // MARK: Остановка

    /**
     * Кладёт комнату. Всё, чем владеет SDK, уходит; строки остаются — на них смотрят. Перед выходом —
     * полоса «Непрочитанные» переезжает в конец, если читающий дочитал (см. [markAsRead]).
     */
    fun stop() {
        generation += 1
        opening = null
        moveReadMarkerIfRead()

        pageInFlight?.cancel()
        pageInFlight = null
        refillJob?.cancel()
        refillJob = null
        listenerHandle?.cancel()
        listenerHandle = null
        diffs?.close()
        diffs = null
        diffsJob?.cancel()
        diffsJob = null
        roomInfoHandle?.cancel()
        roomInfoHandle = null
        typingHandle?.cancel()
        typingHandle = null

        // Уходя посреди слова, не оставлять собеседнику «печатает…», который никто не отзовёт.
        typingStopper?.cancel()
        typingStopper = null
        if (isTellingThemWeType) {
            isTellingThemWeType = false
            scope.launch { runCatching { withTimeLimit(SDK_LIMIT) { room.typingNotice(false) } } }
        }

        timeline?.close()
        timeline = null
        items.clear()
        memo.clear()
        latestRows = emptyList()
        askedForReplyDetails.clear()
        isRefilling = false
        mutableState.update { it.copy(canLoadMore = true, isPaginating = false, typing = emptyList()) }
    }

    /** Комната закрыта насовсем: экран ушёл. Хендл комнаты — нативный объект, его закрывают явно. */
    fun close() {
        stop()
        room.close()
    }

    // MARK: Диффы

    private fun apply(batch: List<TimelineDiff>) {
        val held = items.size
        val wasThrownAway = batch.any { it is TimelineDiff.Clear || it is TimelineDiff.Reset }
        when (val outcome = items.applyDiffs(batch.map { it.asListDiff() })) {
            is DiffOutcome.Desynced -> return desynced(outcome.what)
            DiffOutcome.Applied -> Unit
        }
        // SDK выбросил переписку (после удаления, после реакции) и вернул только живой хвост. Вернуть
        // её — и сказать об этом *до* пересборки, иначе на экран попала бы пустая комната.
        if (held > 0 && items.size < held && wasThrownAway) refill()
        rebuildRows()
    }

    /**
     * Лента — уже не та, что описывает SDK: перечитать. Остановка и запуск дают свежий слушатель,
     * а его первый `reset` — собственный отчёт SDK о том, что он держит.
     */
    private fun desynced(what: String) {
        if (isResyncing) return
        isResyncing = true
        Log.error(Log.Category.TIMELINE, "лента разошлась с SDK: $what; перечитываем комнату")
        val mine = generation
        scope.launch {
            try {
                if (mine != generation) return@launch
                stop()
                start()
            } finally {
                isResyncing = false
            }
        }
    }

    private fun rebuildRows() {
        val rows = TimelineRows.rows(memo.mapped(items, ownUserId, strings))
        latestRows = rows

        rows.lastOrNull { it.message?.isOwn == true && it.message?.delivery == DeliveryStatus.Read }?.message?.timestamp?.let { read ->
            if (read != reportedReadUpTo) {
                reportedReadUpTo = read
                onOwnMessagesRead(read)
            }
        }
        fetchReplyDetails()

        // Половина переписки — не улучшение целой, на которую смотрит читающий.
        if (isRefilling && rows.size < state.value.rows.size) return
        mutableState.update { it.copy(rows = rows) }
    }

    /** Ответы, оригиналов которых SDK не держит, — просит их. Ответ придёт обычным диффом. */
    private fun fetchReplyDetails() {
        val timeline = timeline ?: return
        val missing = memo.unresolvedReplies.filter { it !in askedForReplyDetails }
        if (missing.isEmpty()) return
        askedForReplyDetails += missing
        scope.launch {
            for (eventId in missing) runCatching { withTimeLimit(SDK_LIMIT) { timeline.fetchDetailsForEvent(eventId) } }
        }
    }

    /**
     * Достаёт переписку снова после того, как SDK её выбросил, — на ту глубину, что читающий уже
     * набрал, а не на экран: пролиставшего неделю нельзя бросить у живого конца.
     */
    private fun refill() {
        if (refillJob != null) return
        isRefilling = true
        mutableState.update { it.copy(canLoadMore = true) }
        val depth = state.value.rows.size
        val mine = generation
        refillJob = scope.launch {
            fillPages(depth, MAXIMUM_REFILL_PAGES)
            if (mine != generation) return@launch
            isRefilling = false
            refillJob = null
            mutableState.update { it.copy(rows = latestRows) }
        }
    }

    // MARK: Отправка

    /**
     * Текст. Markdown соблюдается: люди пишут `*так*` и ждут, что сработает. [replyingTo] — ID
     * *сообщения*; ответ на то, у чего ещё нет события, уходит обычным сообщением, а не теряется.
     */
    suspend fun send(text: String, replyingTo: String? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val timeline = timeline ?: return fail(TimelineFailure.SEND)
        stopTellingThemWeType()
        // Не пишется в журнал и никуда, кроме зашифрованной очереди SDK.
        val content = messageEventContentFromMarkdown(trimmed)
        try {
            val eventId = replyingTo?.let(::eventId)
            withTimeLimit(SDK_LIMIT) {
                if (eventId != null) timeline.sendReply(content, eventId) else timeline.send(content).destroy()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "сообщение не отправилось: ${Log.describe(error)}")
            fail(TimelineFailure.SEND)
        }
    }

    suspend fun edit(messageId: String, text: String) {
        val trimmed = text.trim()
        val timeline = timeline ?: return
        val id = eventOrTransactionId(messageId) ?: return
        if (trimmed.isEmpty()) return
        try {
            withTimeLimit(SDK_LIMIT) {
                timeline.edit(id, org.matrix.rustcomponents.sdk.EditedContent.RoomMessage(messageEventContentFromMarkdown(trimmed)))
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "правка не ушла: ${Log.describe(error)}")
            fail(TimelineFailure.EDIT)
        }
    }

    suspend fun delete(messageId: String) {
        val timeline = timeline ?: return
        val id = eventOrTransactionId(messageId) ?: return
        try {
            withTimeLimit(SDK_LIMIT) { timeline.redactEvent(id, null) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "удаление не прошло: ${Log.describe(error)}")
            fail(TimelineFailure.DELETE)
        }
    }

    /** Реакция — переключатель: повторное нажатие своей убирает её. */
    suspend fun toggleReaction(messageId: String, key: String) {
        val timeline = timeline ?: return
        val id = eventOrTransactionId(messageId) ?: return
        try {
            withTimeLimit(SDK_LIMIT) { timeline.toggleReaction(id, key) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "реакция не ушла: ${Log.describe(error)}")
            fail(TimelineFailure.REACTION)
        }
    }

    /**
     * Отправить застрявшее ещё раз. Сбой стопорит одним из двух способов, и человек их не различит:
     * SDK приостановил очередь комнаты или отставил одно сообщение. Снимаются оба. Ничего своего не
     * составляется: это то сообщение, что уже лежит в зашифрованной очереди.
     */
    suspend fun retrySending(messageId: String) {
        runCatching { room.enableSendQueue(true) }
        val handle = sendHandle(messageId) ?: return
        runCatching { withTimeLimit(SDK_LIMIT) { handle.tryResend() } }
    }

    /**
     * Выбросить неушедшее. Ничего не удаляется на сервере и никому не сообщается: событие никуда не
     * дошло. Без подтверждения, в отличие от удаления: то необратимо для всей комнаты, а это — нет.
     */
    suspend fun discard(messageId: String) {
        val handle = sendHandle(messageId) ?: return
        runCatching { withTimeLimit(SDK_LIMIT) { handle.abort() } }
    }

    /**
     * Отправить всё равно — решение о доверии, всегда человека: отозвать подтверждение тех, чьи
     * ключи сменились, или довериться устройствам, которые владелец не подтвердил.
     */
    suspend fun sendAnyway(messageId: String) {
        val (handle, wedge) = failedSend(messageId) ?: return
        runCatching { room.enableSendQueue(true) }
        try {
            withTimeLimit(SDK_LIMIT) {
                when (wedge) {
                    is QueueWedgeError.IdentityViolations -> room.withdrawVerificationAndResend(wedge.users, handle)
                    is QueueWedgeError.InsecureDevices -> room.ignoreDeviceTrustAndResend(wedge.userDeviceMap, handle)
                    else -> handle.tryResend()
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "«отправить всё равно» не прошло: ${Log.describe(error)}")
            fail(TimelineFailure.SEND)
        }
    }

    /** Хендл локального эха — у пришедших с сервера его нет: повторять нечего. */
    private fun sendHandle(messageId: String): SendHandle? = items.firstNotNullOfOrNull { item ->
        val event = item.asEvent() ?: return@firstNotNullOfOrNull null
        if (TimelineMapper.identifier(event.eventOrTransactionId) != messageId) return@firstNotNullOfOrNull null
        event.lazyProvider.getSendHandle()
    }

    private fun failedSend(messageId: String): Pair<SendHandle, QueueWedgeError>? = items.firstNotNullOfOrNull { item ->
        val event = item.asEvent() ?: return@firstNotNullOfOrNull null
        if (TimelineMapper.identifier(event.eventOrTransactionId) != messageId) return@firstNotNullOfOrNull null
        val failed = event.localSendState as? EventSendState.SendingFailed ?: return@firstNotNullOfOrNull null
        event.lazyProvider.getSendHandle()?.let { it to failed.error }
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    private fun fail(failure: TimelineFailure) = mutableState.update { it.copy(failure = failure) }

    /** ID события за сообщением — только у дошедших до сервера. */
    private fun eventId(messageId: String): String? =
        items.firstNotNullOfOrNull { item ->
            val event = item.asEvent() ?: return@firstNotNullOfOrNull null
            val id = event.eventOrTransactionId
            if (TimelineMapper.identifier(id) == messageId) (id as? EventOrTransactionId.EventId)?.eventId else null
        }

    private fun eventOrTransactionId(messageId: String): EventOrTransactionId? =
        items.firstNotNullOfOrNull { item ->
            item.asEvent()?.eventOrTransactionId?.takeIf { TimelineMapper.identifier(it) == messageId }
        }

    // MARK: Прочитанное

    /**
     * Сказать серверу, что человек видел переписку. Два вызова: `markAsRead` не снимает флаг
     * «непрочитано», поставленный вручную, — документация SDK говорит это прямо.
     */
    suspend fun markAsRead() {
        hasReadToEnd = true
        runCatching { withTimeLimit(SDK_LIMIT) { room.markAsRead(ReceiptType.READ) } }
        runCatching { withTimeLimit(SDK_LIMIT) { room.setUnreadFlag(false) } }
    }

    /**
     * Читающий уходит, всё прочитав: маркер полностью прочитанного — в конец, на следующий раз. Не
     * раньше: полоса исчезла бы у читающего из-под глаз, и переписка прыгнула бы на её высоту.
     */
    private fun moveReadMarkerIfRead() {
        if (!hasReadToEnd) return
        hasReadToEnd = false
        scope.launch { runCatching { withTimeLimit(SDK_LIMIT) { room.markAsRead(ReceiptType.FULLY_READ) } } }
    }

    // MARK: Черновики

    /**
     * Полфразы на потом — через SDK, а не в настройках: черновик — это сообщение, и место ему в том же
     * зашифрованном сторе, что и переписке.
     */
    suspend fun draft(): String? = runCatching { withTimeLimit(SDK_LIMIT) { room.loadComposerDraft(null) } }.getOrNull()?.plainText

    suspend fun saveDraft(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            clearDraft()
            return
        }
        runCatching {
            withTimeLimit(SDK_LIMIT) { room.saveComposerDraft(ComposerDraft(text, null, ComposerDraftType.NewMessage, emptyList()), null) }
        }
        onDraftChanged(trimmed)
    }

    suspend fun clearDraft() {
        runCatching { withTimeLimit(SDK_LIMIT) { room.clearComposerDraft(null) } }
        onDraftChanged(null)
    }

    // MARK: «Печатает»

    private suspend fun adoptTyping(userIds: List<String>) {
        val names = userIds.filter { it != ownUserId }.map { id ->
            runCatching { withTimeLimit(SDK_LIMIT) { room.memberDisplayName(id) } }.getOrNull() ?: id
        }
        mutableState.update { it.copy(typing = names) }
    }

    /**
     * Человек пишет. На каждое нажатие, но дёшево: первое сообщает серверу, остальные лишь отодвигают
     * момент, когда мы это заберём. Забрать важнее, чем сообщить: иначе «Аня печатает…» висит, пока
     * Аня ушла обедать.
     */
    fun typed() {
        typingStopper?.cancel()
        if (!isTellingThemWeType) {
            isTellingThemWeType = true
            scope.launch { runCatching { withTimeLimit(SDK_LIMIT) { room.typingNotice(true) } } }
        }
        typingStopper = scope.launch {
            delay(TYPING_LULL)
            stopTellingThemWeType()
        }
    }

    /** Отзыв без ожидания — им пользуется отправка: сообщение не должно ждать круга до сервера. */
    fun stopTellingThemWeType() {
        typingStopper?.cancel()
        typingStopper = null
        if (!isTellingThemWeType) return
        isTellingThemWeType = false
        scope.launch { runCatching { withTimeLimit(SDK_LIMIT) { room.typingNotice(false) } } }
    }

    private companion object {
        const val PAGE_SIZE = 25
        const val MAXIMUM_OPENING_PAGES = 4
        const val MAXIMUM_REFILL_PAGES = 20
        val SDK_LIMIT = 30.seconds
        val TYPING_LULL = 4.seconds
    }
}
