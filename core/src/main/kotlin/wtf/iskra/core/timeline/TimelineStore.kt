package wtf.iskra.core.timeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import wtf.iskra.core.media.MediaSettings
import wtf.iskra.core.mock.JsonValues
import wtf.iskra.core.stickers.Sticker
import wtf.iskra.core.stickers.StickerPicture
import uniffi.matrix_sdk_base.EncryptionState
import wtf.iskra.core.media.PhotoPreparation
import wtf.iskra.core.media.PreparedVideo
import wtf.iskra.core.media.VoiceWaveform
import wtf.iskra.core.media.VideoPreparation
import org.matrix.rustcomponents.sdk.ThumbnailInfo
import org.matrix.rustcomponents.sdk.VideoInfo
import wtf.iskra.core.search.SearchHit
import wtf.iskra.core.search.SearchStore
import wtf.iskra.core.rooms.MessagePreview
import wtf.iskra.core.R
import wtf.iskra.core.rooms.PrivacyStatus
import wtf.iskra.core.rooms.RoomFace
import wtf.iskra.core.rooms.RoomKind
import wtf.iskra.core.rooms.avatarSeed
import wtf.iskra.core.rooms.faces
import wtf.iskra.core.rooms.kind
import wtf.iskra.core.rooms.of
import wtf.iskra.core.rooms.pictureUrl
import wtf.iskra.core.rooms.title
import wtf.iskra.core.rooms.serverTitle
import wtf.iskra.core.rooms.counterpart
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.AssetType
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.QueueWedgeError
import org.matrix.rustcomponents.sdk.SendHandle
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.Mentions
import org.matrix.rustcomponents.sdk.MessageLikeEventType
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RoomMessageEventContentWithoutRelation
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.time.Duration
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
    /** Закреплённые события комнаты, в порядке закрепления: последнее — самое свежее. */
    val pinnedIds: List<String> = emptyList(),
    val failure: TimelineFailure? = null,
    /** Эхо, сменившее наш ожидающий пузырь: ID сообщения → ID, под которым строка уже стояла (см. [viewKey]). */
    val adopted: Map<String, String> = emptyMap(),
) {
    /**
     * Ключ, под которым строку *рисуют*, — не всегда её ID. Расходятся они у одного: своего сообщения
     * между отправкой и эхом. Наш пузырь встаёт по нажатию (`PendingSends`), эхо приходит долей секунды
     * позже с другим ID, и для `LazyColumn` это две разные строки: одна уничтожалась, другая строилась.
     * Эхо наследует ключ пузыря, пока оно на экране. Только для рисования: всё, что *действует* на
     * сообщение, берёт настоящий ID — его знает сервер.
     */
    fun viewKey(row: TimelineRow): String = adopted[row.id] ?: row.id

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

    /** Голос не засчитан или опрос не закрылся. Сам опрос — каким был. */
    POLL(R.string.timeline_poll_failed),

    /** Закрепить или открепить не дали — чаще всего нет прав. */
    PIN(R.string.timeline_pin_failed),

    /** Стикер не ушёл: нет сети, комнату не удалось спросить или картинка не чиста. */
    STICKER(R.string.timeline_sticker_failed),
}

/**
 * Переписка одной комнаты: сообщения и возможность добавить к ним. Порт `TimelineStore.swift`
 * (docs/PROMPT.md, 4.2 и 12.3). Как список комнат, SDK шлёт её диффами, но среди элементов есть
 * локальное эхо — то, что сообщение появляется сразу и потом тихо получает галочку.
 *
 * Пока перенесено основное: открытие с явной первой страницей, пагинация, отправка и ответ, правка,
 * удаление, реакции, прочитанное, черновики, «печатает», ожидающие пузыри. Треды, пересылка и
 * присутствие — следующими пунктами этапа 4.
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
    /**
     * Тред, которым этот стор является: ID события корня. Null — лента самой комнаты: там ответы в
     * тредах строкой под корнем ([Message.thread]), а то и вовсе вне ленты ([ThreadSettings.hidesReplies]).
     * Лента треда начинается корнем и держит только ответы; всё отправленное из неё — слова, картинки,
     * ответ — уходит в тред.
     */
    val threadRootId: String? = null,
    /** Настройки тредов на миг открытия: фокус открытой ленты не меняется. */
    private val threadSettings: () -> ThreadSettings = { ThreadSettings() },
    /** Прозвища личных чатов: шапка, имена на сообщениях собеседника и в цитатах его. */
    private val localNames: wtf.iskra.core.rooms.LocalNames.Account? = null,
    /** Скачать картинку по `mxc://` — стикеру из пака в зашифрованной комнате (`sendSticker`). Null — нечем. */
    private val downloadMedia: (suspend (url: String) -> ByteArray)? = null,
) {
    /** Имя от сервера — чтобы снять прозвище, пока экран открыт, не дожидаясь нового `RoomInfo`. */
    private var serverTitle: String? = null

    /** Собеседник личного чата — его сообщения подписываются прозвищем. */
    private var counterpartId: String? = null
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
    private var localNamesJob: Job? = null
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

    /** Отправленное, чего SDK ещё не вернул: стоит в конце переписки, чтобы нажатие ставило слова на экран. */
    private val pending = PendingSends()

    /** Эхо, сменившее наш пузырь: ID сообщения → ID пузыря. См. [TimelineState.viewKey]. */
    private val adopted = mutableMapOf<String, String>()

    /** Лента закреплённых — по требованию, см. [pinned]. */
    private var pinnedStore: PinnedStore? = null

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
                // Закреплённые, если их уже открывали, — снова с комнатой: [stop] клал и их.
                if (mine == generation) pinnedStore?.let { scope.launch { it.start() } }
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
        localNamesJob = localNames?.let { names ->
            scope.launch { names.changes.collect { changed -> if (changed == roomId && mine == generation) localNameChanged() } }
        }
        roomInfoHandle = room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                scope.launch { if (mine == generation) adopt(roomInfo) else roomInfo.destroy() }
            }
        })

        // Лента комнаты держит ответы в тредах среди всего остального, пока настройки не скажут их
        // убрать, — тогда они только строкой под корнем, и тред открывают, чтобы прочесть.
        val header = state.value.header
        val threads = threadSettings()
        val configuration = TimelineConfiguration(
            focus = threadRootId?.let { TimelineFocus.Thread(it) }
                ?: TimelineFocus.Live(hideThreadedEvents = threads.usesThreads(header.kind, header.memberCount) && threads.hidesReplies),
            filter = TimelineFilter.All,
            internalIdPrefix = threadRootId?.let { "thread-$it-" },
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
        serverTitle = info.serverTitle(ownUserId, strings)
        counterpartId = if (oneToOne) info.counterpart(ownUserId)?.userId else null
        mutableState.update { state ->
            state.copy(
                header = RoomHeader(
                    name = info.title(ownUserId, strings, localNames?.name(roomId)),
                    avatarUrl = info.pictureUrl(ownUserId),
                    avatarSeed = info.avatarSeed(ownUserId),
                    faces = if (info.kind == RoomKind.GROUP) info.faces(ownUserId) else emptyList(),
                    kind = info.kind,
                    isOneToOne = oneToOne,
                    memberCount = info.joinedMembersCount.toInt(),
                    privacy = PrivacyStatus.of(info.encryptionState),
                ),
                // Права приходят не всегда; «нет» — только на ясное слово комнаты.
                pinnedIds = info.pinnedEventIds,
                canPost = levels?.let { runCatching { it.canOwnUserSendMessage(MessageLikeEventType.RoomMessage) }.getOrNull() } ?: state.canPost,
            )
        }
        pinnedStore?.roomPinned(info.pinnedEventIds)
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
        localNamesJob?.cancel()
        localNamesJob = null
        typingHandle?.cancel()
        typingHandle = null
        pinnedStore?.stop()

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
        // Пузыри принадлежали ушедшей ленте, а само сообщение — в очереди SDK: вернётся эхом при открытии.
        pending.removeAll()
        adopted.clear()
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

    /**
     * В личном чате, которому дали имя, собеседник зовётся им и в самой переписке — на своих сообщениях и
     * в каждой цитате их, а не только в шапке и списке: отвечаешь *Маме*, а над цитатой было `anna_1987`.
     */
    private fun applyingLocalName(rows: List<TimelineRow>): List<TimelineRow> {
        val counterpart = counterpartId ?: return rows
        val local = localNames?.name(roomId) ?: return rows
        return rows.map { row ->
            if (row !is TimelineRow.MessageRow) return@map row
            val message = row.message
            val renamed = message.copy(
                senderName = if (message.senderId == counterpart) local else message.senderName,
                replyTo = message.replyTo?.let { reply -> if (reply.senderId == counterpart) reply.copy(senderName = local) else reply },
            )
            if (renamed == message) row else TimelineRow.MessageRow(renamed)
        }
    }

    /** Переименовали этот чат на устройстве: шапка и строки — сразу, нового `RoomInfo` не будет. */
    private fun localNameChanged() {
        val server = serverTitle ?: return
        mutableState.update { state ->
            val header = state.header
            val local = if (header.kind == RoomKind.DIRECT) localNames?.name(roomId) else null
            state.copy(header = header.copy(name = local ?: server))
        }
        rebuildRows()
    }

    private fun rebuildRows() {
        // Альбомы — последним проходом: заходы, даты и «прочитано» считаются по отдельным сообщениям.
        var rows = Album.grouping(applyingLocalName(TimelineRows.rows(memo.mapped(items, ownUserId, strings))))

        // Отправленное и ещё не показанное — в конец, и снимается, как только среди строк выше
        // появится его эхо. Снимается *до* добавления: эхо приходит обычным диффом, и это та самая
        // сборка — добавив сначала, одна фраза стояла бы в переписке дважды на кадр.
        if (!pending.isEmpty) {
            adopted += pending.pruneEchoed(rows)
            rows = TimelineRows.markRunEnds(rows + pendingRows(after = rows))
        }
        // Только пока сообщение на экране: отлистанное далеко назад уходит, и ключ за ним никому не нужен.
        if (adopted.isNotEmpty()) {
            val present = rows.mapTo(HashSet()) { it.id }
            adopted.keys.retainAll(present)
        }
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
        val keys = adopted.toMap()
        mutableState.update { it.copy(rows = rows, adopted = keys) }
    }

    /**
     * Ещё не ушедшее — строками в конце переписки. Пузырь — настоящий [Message] с настоящим
     * [DeliveryStatus.Sending]: рисуется на нашей стороне с часами, на него не отреагировать, его не
     * изменить и не удалить (`canReact` и соседи держатся за тот же статус), и хендла отправки по его
     * ID никто не найдёт — он не принадлежит ни одному событию.
     *
     * Даты над ним не придумывается: дату ставит лента SDK, и угаданная встала бы второй строкой за миг
     * до настоящей. Платит только первое сообщение нового дня — дата появится с эхом.
     */
    private fun pendingRows(after: List<TimelineRow>): List<TimelineRow> {
        // Мы — как эта комната нас уже рисует: имя и лицо — у члена комнаты, спросить его — вызов и
        // ожидание, а ждать — единственное, чего сообщение «сейчас» не может себе позволить.
        val me = after.asReversed().firstNotNullOfOrNull { row -> row.message?.takeIf { it.isOwn } }
        var previous = after.lastOrNull()?.message
        return pending.all.map { item ->
            // Заход — по правилу ленты: три строки подряд не несут каждая наше имя, и ничего не
            // прыгает, когда эхо сменит пузырь строкой, которую лента сгруппировала сама.
            val above = previous
            val isContinuation = above != null && above.senderId == ownUserId &&
                Duration.between(above.timestamp, item.sentAt) < TimelineRows.RUN_WINDOW
            val message = Message(
                id = item.id,
                kind = MessageKind.Text(item.text),
                senderId = ownUserId,
                senderName = me?.senderName ?: ownUserId,
                senderAvatarUrl = me?.senderAvatarUrl,
                isOwn = true,
                timestamp = item.sentAt,
                delivery = DeliveryStatus.Sending,
                replyTo = item.replyingTo?.let { quote(of = it, rows = after) },
                isContinuation = isContinuation,
            )
            previous = message
            TimelineRow.MessageRow(message)
        }
    }

    /**
     * Полоска над ещё не ушедшим ответом — из отвеченного, как оно стоит на экране. Null, если его в
     * переписке уже нет: ответ без полоски лучше полоски «сообщение недоступно» о том, что на виду, —
     * эхо через миг принесёт цитату SDK.
     */
    private fun quote(of: String, rows: List<TimelineRow>): ReplyPreview? {
        val original = rows.message(withId = of) ?: return null
        val eventId = eventId(of) ?: return null
        val content = items.firstNotNullOfOrNull { item ->
            item.asEvent()?.takeIf { TimelineMapper.identifier(it.eventOrTransactionId) == of }?.content
        }
        val text = original.editableText ?: content?.let { MessagePreview.text(it, strings) } ?: return null
        return ReplyPreview(eventId, original.senderName, original.senderId, text)
    }

    /**
     * Ничего не сообщается и ничего не теряется: сообщение в очереди SDK и встанет обычным пузырём, как
     * лента его догонит. Это только не даёт строке, живущей ради сотни миллисекунд, остаться навсегда,
     * если эхо выглядело не так, как мы ждали.
     */
    private fun forgetPending(id: String) {
        val mine = generation
        scope.launch {
            delay(PendingSends.LIFETIME.toMillis())
            if (mine != generation || pending.all.none { it.id == id }) return@launch
            pending.remove(id)
            rebuildRows()
        }
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
    suspend fun send(text: String, replyingTo: String? = null, mentions: List<ComposerMention> = emptyList()) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val timeline = timeline ?: return fail(TimelineFailure.SEND)
        // Не пишется в журнал и никуда, кроме зашифрованной очереди SDK.
        val (markdown, userIds) = ComposerMentions.markdown(trimmed, mentions)

        // Слова — на экран *сначала*, отправка — за ними. Эхо SDK приходит почти сразу, но эти сотни
        // миллисекунд человек смотрел на пустой композер и неизменную переписку. См. `PendingSends`.
        val shown = state.value.rows.mapNotNullTo(HashSet()) { row -> row.message?.takeIf { it.isOwn }?.id }
        val pendingId = pending.add(trimmed, replyingTo, body = markdown, alreadyShown = shown)
        rebuildRows()
        forgetPending(pendingId)

        // Отправка — конец письма; и не ждётся: запрос «перестал печатать» не должен стоять между
        // нажатием и очередью.
        stopTellingThemWeType()
        val content = markdownContent(markdown, userIds)
        try {
            val eventId = replyingTo?.let(::eventId)
            withTimeLimit(SDK_LIMIT) {
                if (eventId != null) timeline.sendReply(content, eventId) else timeline.send(content).destroy()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "сообщение не отправилось: ${Log.describe(error)}")
            // SDK не взял его вовсе: эха не будет, в очереди пусто, и пузырь врал бы, что сообщение в пути.
            pending.remove(pendingId)
            rebuildRows()
            fail(TimelineFailure.SEND)
        }
    }

    /**
     * Слова с упоминаниями: уцелевшие `@Имя` — ссылками `matrix.to` в Markdown и их ID — в
     * `m.mentions`, который и будит людей (`ComposerMentions.markdown`).
     */
    private fun content(text: String, mentions: List<ComposerMention>): RoomMessageEventContentWithoutRelation {
        val (markdown, userIds) = ComposerMentions.markdown(text, mentions)
        return markdownContent(markdown, userIds)
    }

    private fun markdownContent(markdown: String, userIds: List<String>): RoomMessageEventContentWithoutRelation {
        val content = messageEventContentFromMarkdown(markdown)
        return if (userIds.isEmpty()) content else content.withMentions(Mentions(userIds, false))
    }

    /**
     * Кого можно позвать через «@»: вошедшие в комнату, кроме себя, — те, кто писал недавно, первыми.
     * Без синхронизации участников: список из стора SDK, а ждать сервер ради подсказки не стоит.
     */
    suspend fun mentionCandidates(): List<MentionCandidate> {
        val members = runCatching {
            withTimeLimit(SDK_LIMIT) {
                val iterator = room.membersNoSync()
                buildList {
                    while (true) {
                        val chunk = iterator.nextChunk(MEMBER_CHUNK.toUInt()) ?: break
                        if (chunk.isEmpty()) break
                        addAll(chunk)
                    }
                }
            }
        }.getOrElse {
            Log.error(Log.Category.TIMELINE, "участники не прочитались: ${Log.describe(it)}")
            return emptyList()
        }
        val recent = state.value.rows.asReversed().mapNotNull { (it as? TimelineRow.MessageRow)?.message?.senderId }.distinct()
        return members
            .filter { it.membership is MembershipState.Join && it.userId != ownUserId }
            .map { MentionCandidate(it.userId, it.displayName?.takeIf(String::isNotBlank) ?: ComposerMentions.localpart(it.userId), it.avatarUrl) }
            .sortedBy { candidate -> recent.indexOf(candidate.userId).let { if (it < 0) Int.MAX_VALUE else it } }
    }

    /**
     * Отправить фото — байтами, а не путём (`send(photo:)` на iOS): открытый текст на диск не
     * ложится, и нет файла, чью жизнь надо угадывать. Через эту функцию проходит **каждый** путь
     * фото наружу — выбор, вставка, пересылка: подготовка по `MediaSettings` здесь, а не у экрана.
     *
     * Не очистилось, когда просили очистить, — не уходит вовсе (false). `caption` — подпись в том же
     * событии, что фото.
     */
    suspend fun sendPhoto(original: ByteArray, filename: String, settings: MediaSettings, caption: String? = null, replyingTo: String? = null): Boolean {
        val timeline = timeline ?: run {
            fail(TimelineFailure.SEND)
            return false
        }
        // Не на главном потоке: перерисовать двенадцать мегапикселей — сотни миллисекунд.
        val prepared = withContext(Dispatchers.Default) { PhotoPreparation.prepare(original, filename, settings) }
        if (prepared == null) {
            Log.error(Log.Category.MEDIA, "фото не отправлено: метаданные не убрались")
            fail(TimelineFailure.SEND)
            return false
        }
        val parameters = UploadParameters(
            source = UploadSource.Data(prepared.bytes, prepared.filename),
            caption = caption?.trim()?.ifEmpty { null },
            formattedCaption = null,
            mentions = null,
            inReplyTo = replyingTo?.let(::eventId),
        )
        val info = ImageInfo(
            height = prepared.height.toULong(),
            width = prepared.width.toULong(),
            mimetype = prepared.mimeType,
            size = prepared.bytes.size.toULong(),
            thumbnailInfo = null,
            thumbnailSource = null,
            blurhash = prepared.blurhash,
            isAnimated = false,
        )
        return try {
            val handle = withTimeLimit(SDK_LIMIT) { timeline.sendImage(parameters, null, info) }
            // Загрузка идёт в очереди SDK; ждать её здесь — держать экран. Сбой видно на пузыре.
            scope.launch {
                try {
                    handle.join()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.error(Log.Category.MEDIA, "фото не загрузилось: ${Log.describe(error)}")
                } finally {
                    handle.destroy()
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "фото не отправилось: ${Log.describe(error)}")
            fail(TimelineFailure.SEND)
            false
        }
    }

    /**
     * Место — точкой (`assetType = PIN`): *место*, а не «где отправитель». Matrix знает и второе
     * (`SENDER`), но это было бы утверждение о человеке от его имени, которого он не видел на экране,
     * где нажимал «Отправить». Тело — фраза с координатами для клиентов, не знающих событий места
     * (порт `send(location:)`).
     */
    suspend fun sendLocation(latitude: Double, longitude: Double, name: String? = null, replyingTo: String? = null): Boolean {
        val timeline = timeline ?: run {
            fail(TimelineFailure.SEND)
            return false
        }
        return try {
            withTimeLimit(SDK_LIMIT) {
                timeline.sendLocation(
                    // Точка, а не запятая: «55,755800, 37,617300» на немецком — две пары чисел, а не одна.
                    String.format(java.util.Locale.ROOT, strings[R.string.timeline_location_body], latitude, longitude),
                    "geo:$latitude,$longitude",
                    name,
                    LOCATION_ZOOM,
                    AssetType.PIN,
                    replyingTo?.let(::eventId),
                )
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "место не отправилось: ${Log.describe(error)}")
            fail(TimelineFailure.SEND)
            false
        }
    }

    /**
     * Стикер из пака — двумя дорогами, по комнате (порт `send(sticker:)`):
     * - **точно не зашифрована**: настоящий `m.sticker` через `Room.sendRaw`, ссылкой на картинку пака;
     * - **иначе** (почти любой настоящий разговор): картинка скачивается и уходит *зашифрованной картинкой*
     *   `sticker.<расширение>` через очередь отправки (`StickerPicture`). `sendRaw` — мимо очереди, и всё,
     *   что очередь делает тихо и с повторами (ключи, участники, остановка на проверенном человеке), случилось
     *   бы здесь одним таймаутом: «стикер не ушёл». Через очередь он ждёт сеть как сообщение, а остановка —
     *   «Не отправлено» с «Отправить всё равно» на пузыре.
     */
    suspend fun sendSticker(sticker: Sticker, replyingTo: String? = null): Boolean =
        if (isKnownUnencrypted()) sendRawSticker(sticker, replyingTo) else sendSealedSticker(sticker, replyingTo)

    /** Только «точно нет»: «наверное нет» — не ответ на вопрос, можно ли картинке уйти открытой. Спрашивается у сервера. */
    private suspend fun isKnownUnencrypted(): Boolean = try {
        withTimeLimit(SDK_LIMIT) { room.latestEncryptionState() } == EncryptionState.NOT_ENCRYPTED
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }

    private suspend fun sendRawSticker(sticker: Sticker, replyingTo: String?): Boolean = try {
        val content = JsonValues.encode(sticker.eventContent(replyingTo?.let(::eventId)))
        withTimeLimit(SDK_LIMIT) { room.sendRaw("m.sticker", content) }
        true
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        Log.error(Log.Category.MEDIA, "стикер не отправился: ${Log.describe(error)}")
        fail(TimelineFailure.STICKER)
        false
    }

    private suspend fun sendSealedSticker(sticker: Sticker, replyingTo: String?): Boolean {
        val timeline = timeline
        val download = downloadMedia
        if (timeline == null || download == null) {
            fail(TimelineFailure.STICKER)
            return false
        }
        val prepared = try {
            val bytes = withTimeLimit(STICKER_DOWNLOAD_LIMIT) { download(sticker.url) }
            withContext(Dispatchers.Default) { StickerPicture.prepare(bytes) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "картинка стикера не скачалась: ${Log.describe(error)}")
            null
        }
        if (prepared == null) {
            fail(TimelineFailure.STICKER)
            return false
        }
        val parameters = UploadParameters(
            source = UploadSource.Data(prepared.bytes, prepared.filename),
            caption = null,
            formattedCaption = null,
            mentions = null,
            inReplyTo = replyingTo?.let(::eventId),
        )
        val info = ImageInfo(
            height = prepared.height.toULong(),
            width = prepared.width.toULong(),
            mimetype = prepared.mimeType,
            size = prepared.bytes.size.toULong(),
            thumbnailInfo = null,
            thumbnailSource = null,
            blurhash = prepared.blurhash,
            isAnimated = false,
        )
        return try {
            val handle = withTimeLimit(SDK_LIMIT) { timeline.sendImage(parameters, null, info) }
            scope.launch {
                try {
                    handle.join()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.error(Log.Category.MEDIA, "стикер не загрузился: ${Log.describe(error)}")
                } finally {
                    handle.destroy()
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "стикер не отправился: ${Log.describe(error)}")
            fail(TimelineFailure.STICKER)
            false
        }
    }

    /**
     * Видео — по настройкам: сжатое и без места, дат и устройства (`VideoPreparation`). Не очистилось —
     * не уходит, как фото. SDK получает *путь*: сотни мегабайт байтами держали бы память; копия живёт,
     * пока идёт загрузка, и удаляется, когда SDK её дочитал, — или при следующем запуске.
     */
    suspend fun sendVideo(
        context: android.content.Context,
        source: android.net.Uri,
        filename: String,
        settings: MediaSettings,
        caption: String? = null,
        replyingTo: String? = null,
    ): Boolean {
        val timeline = timeline ?: run {
            fail(TimelineFailure.SEND)
            return false
        }
        val prepared = VideoPreparation.prepare(context, source, filename, VideoPreparation.outgoing(context.cacheDir), settings)
        if (prepared == null) {
            fail(TimelineFailure.SEND)
            return false
        }
        return sendPrepared(timeline, prepared, caption, replyingTo)
    }

    /**
     * Кружок: запись камеры, квадрат без метаданных (`VideoPreparation.prepareNote`) — обычным `m.video`
     * через SDK, чтобы лицо шло зашифрованным (`VideoNote`). Без подписи: кружок — реплика.
     */
    suspend fun sendVideoNote(context: android.content.Context, source: android.net.Uri, replyingTo: String? = null): Boolean {
        val timeline = timeline ?: run {
            fail(TimelineFailure.SEND)
            return false
        }
        val prepared = VideoPreparation.prepareNote(context, source, VideoPreparation.outgoing(context.cacheDir))
        if (prepared == null) {
            fail(TimelineFailure.SEND)
            return false
        }
        return sendPrepared(timeline, prepared, caption = null, replyingTo = replyingTo)
    }

    private suspend fun sendPrepared(timeline: Timeline, prepared: PreparedVideo, caption: String?, replyingTo: String?): Boolean {
        val parameters = UploadParameters(
            source = UploadSource.File(prepared.file.absolutePath),
            caption = caption?.trim()?.ifEmpty { null },
            formattedCaption = null,
            mentions = null,
            inReplyTo = replyingTo?.let(::eventId),
        )
        // Кадр едет своей загрузкой и только вместе с описанием: без `ThumbnailInfo` SDK молча не шлёт
        // ни того, ни другого, и в зашифрованной комнате получателю не из чего сделать картинку.
        val thumbnail = prepared.thumbnail
        val info = VideoInfo(
            duration = java.time.Duration.ofMillis(prepared.durationMs),
            height = prepared.height.toULong(),
            width = prepared.width.toULong(),
            mimetype = prepared.mimeType,
            size = prepared.file.length().toULong(),
            thumbnailInfo = thumbnail?.let { ThumbnailInfo(it.height.toULong(), it.width.toULong(), "image/jpeg", it.jpeg.size.toULong()) },
            thumbnailSource = null,
            blurhash = prepared.blurhash,
        )
        return try {
            val handle = withTimeLimit(SDK_LIMIT) {
                timeline.sendVideo(parameters, thumbnail?.let { UploadSource.Data(it.jpeg, "thumbnail.jpg") } ?: UploadSource.Data(ByteArray(0), "thumbnail.jpg"), info)
            }
            scope.launch {
                try {
                    handle.join()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.error(Log.Category.MEDIA, "видео не загрузилось: ${Log.describe(error)}")
                } finally {
                    handle.destroy()
                    withContext(Dispatchers.IO) { prepared.file.delete() }
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "видео не отправилось: ${Log.describe(error)}")
            prepared.file.delete()
            fail(TimelineFailure.SEND)
            false
        }
    }

    /**
     * Отправить файл — как он есть (docs/PROMPT.md, 8.3: «файлы, отправленные *как файлы*, уходят
     * нетронутыми, намеренно»): ни сжатия, ни очистки — человек выбрал файл, а не фото. Байтами,
     * как и фото: открытый текст не ложится на диск ради того, чтобы SDK прочёл путь.
     */
    suspend fun sendFile(bytes: ByteArray, filename: String, mimeType: String?, caption: String? = null, replyingTo: String? = null): Boolean {
        val timeline = timeline ?: run {
            fail(TimelineFailure.SEND)
            return false
        }
        val parameters = UploadParameters(
            source = UploadSource.Data(bytes, filename),
            caption = caption?.trim()?.ifEmpty { null },
            formattedCaption = null,
            mentions = null,
            inReplyTo = replyingTo?.let(::eventId),
        )
        val info = FileInfo(mimetype = mimeType ?: OCTET_STREAM, size = bytes.size.toULong(), thumbnailInfo = null, thumbnailSource = null)
        return try {
            val handle = withTimeLimit(SDK_LIMIT) { timeline.sendFile(parameters, info) }
            scope.launch {
                try {
                    handle.join()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.error(Log.Category.MEDIA, "файл не загрузился: ${Log.describe(error)}")
                } finally {
                    handle.destroy()
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "файл не отправился: ${Log.describe(error)}")
            fail(TimelineFailure.SEND)
            false
        }
    }

    /**
     * Отправить голосовое: файл записи байтами, длительность и волна (0…1, в шкалу события
     * переводит `VoiceWaveform.wire`) — обе стороны рисуют одну картинку (`send(voice:)` на iOS).
     */
    suspend fun sendVoice(bytes: ByteArray, filename: String, mimeType: String, durationMs: Long, waveform: List<Float>, replyingTo: String? = null): Boolean {
        val timeline = timeline ?: run {
            fail(TimelineFailure.SEND)
            return false
        }
        val parameters = UploadParameters(UploadSource.Data(bytes, filename), null, null, null, replyingTo?.let(::eventId))
        val info = AudioInfo(java.time.Duration.ofMillis(durationMs), bytes.size.toULong(), mimeType)
        return try {
            val handle = withTimeLimit(SDK_LIMIT) { timeline.sendVoiceMessage(parameters, info, VoiceWaveform.wire(waveform)) }
            scope.launch {
                try {
                    handle.join()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.error(Log.Category.MEDIA, "голосовое не загрузилось: ${Log.describe(error)}")
                } finally {
                    handle.destroy()
                }
            }
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "голосовое не отправилось: ${Log.describe(error)}")
            fail(TimelineFailure.SEND)
            false
        }
    }

    suspend fun edit(messageId: String, text: String, mentions: List<ComposerMention> = emptyList()) {
        val trimmed = text.trim()
        val timeline = timeline ?: return
        val id = eventOrTransactionId(messageId) ?: return
        if (trimmed.isEmpty()) return
        try {
            withTimeLimit(SDK_LIMIT) {
                timeline.edit(id, org.matrix.rustcomponents.sdk.EditedContent.RoomMessage(content(trimmed, mentions)))
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
     * Ответить на опрос: весь свой выбор целиком — ответ заменяет прежний. Пустой список снимает
     * голос (в опросе с несколькими ответами — последняя снятая галочка).
     */
    suspend fun vote(messageId: String, answers: List<String>) {
        val timeline = timeline ?: return
        val id = eventId(messageId) ?: return
        try {
            withTimeLimit(SDK_LIMIT) { timeline.sendPollResponse(id, answers) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "голос не ушёл: ${Log.describe(error)}")
            fail(TimelineFailure.POLL)
        }
    }

    /** Завершить свой опрос: голосовать больше нельзя, результаты видят все. */
    suspend fun endPoll(messageId: String) {
        val timeline = timeline ?: return
        val id = eventId(messageId) ?: return
        try {
            withTimeLimit(SDK_LIMIT) { timeline.endPoll(id, strings.get(R.string.timeline_poll_ended_body)) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "опрос не закрылся: ${Log.describe(error)}")
            fail(TimelineFailure.POLL)
        }
    }

    /**
     * Поиск внутри этой комнаты — по тому же локальному индексу, что и общий (`RoomSearchStore` на
     * iOS), и с тем же правилом: результат — только живое сообщение (`SearchStore.excerpt`).
     */
    suspend fun search(query: String): List<SearchHit> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()
        return try {
            withTimeLimit(SDK_LIMIT) {
                room.searchMessages(trimmed, SEARCH_BATCH.toUInt()).nextEvents().orEmpty().mapNotNull { event ->
                    val excerpt = SearchStore.excerpt(event.content, strings) ?: return@mapNotNull null
                    val name = (event.senderProfile as? ProfileDetails.Ready)?.displayName?.takeIf { it.isNotBlank() } ?: event.sender
                    SearchHit(event.eventId, roomId, name, excerpt, MessagePreview.instant(event.timestamp.toULong()))
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "поиск в чате не удался: ${Log.describe(error)}")
            emptyList()
        }
    }

    /**
     * Закреплённые этой комнаты. Один на стор и живёт с ним: [stop] кладёт и его, следующее открытие
     * комнаты поднимает снова. Открывает его экран — когда закреплённое есть.
     */
    fun pinned(): PinnedStore = pinnedStore ?: PinnedStore(room, ownUserId, strings, scope) { fail(TimelineFailure.PIN) }
        .also {
            pinnedStore = it
            it.roomPinned(state.value.pinnedIds)
        }

    /** Закрепить сообщение в комнате или открепить — для всех. */
    suspend fun setPinned(messageId: String, pinned: Boolean) {
        val timeline = timeline ?: return
        val id = eventId(messageId) ?: return
        try {
            withTimeLimit(SDK_LIMIT) { if (pinned) timeline.pinEvent(id) else timeline.unpinEvent(id) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.TIMELINE, "закрепление не изменилось: ${Log.describe(error)}")
            fail(TimelineFailure.PIN)
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
        // Тред читается в своей ленте: квитанции в нём свои, и прочесть тред — не прочесть комнату.
        if (threadRootId != null) {
            timeline?.let { runCatching { withTimeLimit(SDK_LIMIT) { it.markAsRead(ReceiptType.READ) } } }
            return
        }
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
    suspend fun draft(): String? = runCatching { withTimeLimit(SDK_LIMIT) { room.loadComposerDraft(threadRootId) } }.getOrNull()?.plainText

    suspend fun saveDraft(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            clearDraft()
            return
        }
        runCatching {
            withTimeLimit(SDK_LIMIT) { room.saveComposerDraft(ComposerDraft(text, null, ComposerDraftType.NewMessage, emptyList()), threadRootId) }
        }
        onDraftChanged(trimmed)
    }

    suspend fun clearDraft() {
        runCatching { withTimeLimit(SDK_LIMIT) { room.clearComposerDraft(threadRootId) } }
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
        val STICKER_DOWNLOAD_LIMIT = 60.seconds

        /** Насколько близко открывать карту у получателя: улица — видно, какой дом и на какой улице. */
        val LOCATION_ZOOM: UByte = 15u

        const val OCTET_STREAM = "application/octet-stream"
        const val SEARCH_BATCH = 50

        /** Сколько участников читается за раз из стора SDK. */
        const val MEMBER_CHUNK = 200
        val TYPING_LULL = 4.seconds
    }
}
