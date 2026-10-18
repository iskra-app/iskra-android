package wtf.iskra.core.mock

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import wtf.iskra.core.support.Log
import java.net.URLDecoder
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Домашний сервер Matrix внутри приложения — смотреть каждый экран без аккаунта. Порт
 * `MockHomeserver.swift` (docs/PROMPT.md, 14); ответы переносятся один в один.
 *
 * Весь фокус в том, что это обычный HTTP на loopback ([LoopbackHttpServer]): демо — **настоящее
 * приложение**, настоящий SDK, sliding sync, сторы, ленты и очередь отправки, говорящие с сервером,
 * который просто выдуман. Выше `:core` никто не знает разницы, и никого не пришлось учить притворяться.
 *
 * Говорит ровно на той части API, которой пользуется приложение: вход паролем, упрощённый sliding
 * sync (MSC4186) с long-poll, история, отправка, правки, реакции, удаление, квитанции, «печатает»,
 * состояние, участники, приглашения, вход и выход, пространства, каталог, профили, медиа, правила
 * уведомлений, account data и столько ключей, сколько нужно криптографии SDK, чтобы настроиться.
 * Комнаты **не шифруются**: люди выдуманы, устройств у них нет.
 *
 * Состояние в памяти, под одним замком (на iOS — `actor`). Имя сервера `iskra.demo` — ни один
 * настоящий сервер так не адресуется.
 */
internal class MockHomeserver(world: MockWorld = MockWorld()) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val me: String = world.me
    private val rooms = world.rooms
    private val profiles = world.profiles
    private val globalAccountData = world.accountData
    private val media = world.media
    private val publicRoomIds = world.publicRoomIds
    private val aliases = linkedMapOf<String, String>()

    private var accountDataRevision = 0

    /** Растёт от любой перемены; ждущая синхронизация просыпается, когда он сдвинулся. */
    @Volatile
    private var revision = 0
    private val positions = linkedMapOf<Int, Snapshot>()
    private var nextPosition = 1
    private val sentTransactions = hashMapOf<String, String>()

    private val deviceId = "IKSRADEMO"

    /** Другие сеансы демо-аккаунта — экрану устройств есть что показать и что завершить. */
    private val otherDevices = mutableListOf(
        json(
            "device_id" to "MACBOOKDEMO", "display_name" to "Iskra/1.0 (MacBook Air; macOS 26.0)",
            "last_seen_ts" to System.currentTimeMillis() - 3 * HOUR, "last_seen_ip" to "93.184.216.34",
        ),
        json(
            "device_id" to "WEBDEMO", "display_name" to "Element Web: Firefox on Linux",
            "last_seen_ts" to System.currentTimeMillis() - 6 * DAY, "last_seen_ip" to "198.51.100.7",
        ),
    )
    private var deviceKeys: JSON? = null
    private val crossSigning = linkedMapOf<String, JSON>()
    private var oneTimeKeys = 0
    private var backup: JSON? = null
    private var backupKeys = 0

    /** Сколько идёт ответ — чтобы «печатает…» было видно. */
    @Volatile
    var replyDelay: Duration = 3.seconds

    /** Пока выставлено, запрос синхронизации берётся и не отвечается — соединение, умершее в кармане. */
    @Volatile
    var holdsSync = false

    /** Всё, что ушло в «не знаю», — первое, куда смотреть, когда экран ведёт себя странно только в демо. */
    private val unrecognizedRequests = mutableListOf<String>()
    val unrecognized: List<String> get() = synchronized(lock) { unrecognizedRequests.toList() }

    /** Что уже сказано одной позиции sliding sync. Ответ — всегда разница между миром и снимком. */
    private data class Snapshot(
        val delivered: MutableMap<String, Int> = hashMapOf(),
        val revisions: MutableMap<String, Int> = hashMapOf(),
        val joined: MutableSet<String> = hashSetOf(),
        val invited: MutableSet<String> = hashSetOf(),
        var accountData: Int = -1,
    ) {
        fun copy() = Snapshot(HashMap(delivered), HashMap(revisions), HashSet(joined), HashSet(invited), accountData)
    }

    private class Failure(val status: Int, val code: String, message: String = "") : Exception(message) {
        val response get() = error(status, code, message.orEmpty())
    }

    // MARK: Для тестов

    fun receipt(user: String, roomId: String): String? = synchronized(lock) { rooms[roomId]?.receipts?.get(user)?.eventId }

    fun say(text: String, roomId: String, sender: String) = synchronized(lock) {
        append(roomId, sender, "m.room.message", json("msgtype" to "m.text", "body" to text))
    }

    // MARK: HTTP

    suspend fun handle(request: HttpRequest): HttpResponse {
        val (path, query) = split(request.target)
        val body = JsonValues.decodeObject(request.body.toString(Charsets.UTF_8))
        try {
            route(request.method, path, query, body, request)?.let { return it }
        } catch (failure: Failure) {
            return failure.response
        }
        // Записывается: незнакомый эндпоинт — первое, что искать, когда экран в демо странен.
        // Пути несут ID комнат и событий — выдуманные.
        val what = "${request.method} ${path.joinToString("/")}"
        synchronized(lock) { unrecognizedRequests += what }
        Log.info(Log.Category.SESSION, "демо-сервер: ничего по адресу $what")
        return error(404, "M_UNRECOGNIZED", "Unrecognized request")
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun route(method: String, path: List<String>, query: Map<String, String>, body: JSON, raw: HttpRequest): HttpResponse? {
        if (path.size < 2 || path[0] != "_matrix") {
            return if (path.firstOrNull() == ".well-known") error(404, "M_NOT_FOUND") else null
        }
        // Медиа — под старым или аутентифицированным префиксом.
        if (path[1] == "media" || (path.size > 3 && path[1] == "client" && path[3] == "media")) {
            return synchronized(lock) { mediaRoute(method, path.drop(if (path[1] == "media") 3 else 4), raw) }
        }
        if (path[1] != "client") return null
        if (path.size == 3 && path[2] == "versions") return ok(versions)

        val segments = path.drop(3)
        val head = segments.firstOrNull().orEmpty()

        // Ожидающая синхронизация — вне замка: она ждёт, пока мир сдвинется, и держать его не должна.
        if ((head == "org.matrix.simplified_msc3575" && segments.lastOrNull() == "sync") || head == "sync") {
            if (holdsSync && head != "sync") delay(HELD_SYNC)
            return slidingSync(query, body)
        }

        return synchronized(lock) {
            when {
                method == "GET" && head == "login" -> ok(json("flows" to listOf(json("type" to "m.login.password"))))
                method == "POST" && head == "login" -> login()
                method == "POST" && head == "logout" -> ok(json())
                method == "GET" && head == "account" && segments.getOrNull(1) == "whoami" -> ok(json("user_id" to me, "device_id" to deviceId))
                method == "GET" && head == "account" && segments.getOrNull(1) == "3pid" -> ok(json("threepids" to listOf<Any>()))
                method == "GET" && head == "capabilities" -> ok(
                    json(
                        "capabilities" to json(
                            "m.change_password" to json("enabled" to false),
                            "m.set_displayname" to json("enabled" to true),
                            "m.set_avatar_url" to json("enabled" to true),
                        ),
                    ),
                )
                head == "rooms" && segments.size >= 3 -> roomRoute(method, segments[1], segments.drop(2), query, body)
                method == "POST" && (head == "join" || head == "knock") && segments.size > 1 -> join(segments[1])
                method == "POST" && head == "createRoom" -> createRoom(body)
                method == "GET" && head == "joined_rooms" -> ok(json("joined_rooms" to rooms.keys.filter { membership(me, it) == "join" }))
                head == "profile" && segments.size > 1 -> profileRoute(method, segments, body)
                head == "user" && segments.size > 2 -> userRoute(method, segments.drop(2), body)
                method == "POST" && head == "user_directory" -> searchDirectory(body)
                head == "publicRooms" -> publicRooms()
                head == "directory" -> directory(method, segments.drop(1), body)
                head == "pushrules" -> pushRules(method, segments.drop(1), body)
                method == "GET" && head == "pushers" -> ok(json("pushers" to listOf<Any>()))
                method == "POST" && head == "pushers" -> ok(json())
                head == "presence" -> ok(json("presence" to "online"))
                method == "GET" && head == "devices" && segments.size == 1 -> ok(json("devices" to listOf(deviceDescription()) + otherDevices))
                method == "GET" && head == "devices" -> ok(deviceDescription())
                method == "DELETE" && head == "devices" && segments.size == 2 -> {
                    otherDevices.removeAll { it["device_id"] == segments[1] }
                    ok(json())
                }
                method == "POST" && head == "delete_devices" -> {
                    val ended = body["devices"].list().orEmpty().toSet()
                    otherDevices.removeAll { it["device_id"] in ended }
                    ok(json())
                }
                (method == "PUT" || method == "DELETE") && head == "devices" -> ok(json())
                head == "keys" -> keysRoute(segments.drop(1), body)
                head == "room_keys" -> roomKeysRoute(method, segments.drop(1), body)
                method == "PUT" && head == "sendToDevice" -> ok(json())
                method == "GET" && (head == "thirdparty" || head == "voip") -> ok(json())
                else -> null
            }
        }
    }

    /**
     * Каждое демо начинается с пустого стора SDK, значит каждый вход — новое устройство с новыми
     * ключами. Личность, оставшаяся от прошлого, была бы личностью, которой это устройство не может
     * доказать, — и приложение открывалось бы на «подтвердите это устройство» каждый раз.
     */
    private fun login(): HttpResponse {
        deviceKeys = null
        crossSigning.clear()
        oneTimeKeys = 0
        backup = null
        globalAccountData.keys.removeAll {
            it.startsWith("m.secret_storage") || it.startsWith("m.cross_signing") || it.startsWith("m.megolm_backup")
        }
        touchAccountData()
        return ok(json("user_id" to me, "access_token" to "demo-access-token", "device_id" to deviceId, "home_server" to SERVER_NAME))
    }

    // MARK: Sliding sync

    private suspend fun slidingSync(query: Map<String, String>, body: JSON): HttpResponse {
        val position = query["pos"]?.toIntOrNull()
        val (since, isFirst) = synchronized(lock) {
            val known = position?.let { positions[it] }
            (known?.copy() ?: Snapshot()) to (position == null || known == null)
        }
        val timeout = query["timeout"]?.toIntOrNull()?.coerceAtMost(MAX_POLL_MS) ?: if (isFirst) 0 else DEFAULT_POLL_MS
        val limit = timelineLimit(body)

        val deadline = System.currentTimeMillis() + timeout
        var delta = synchronized(lock) { syncDelta(since, limit) }
        while (!delta.changed && !isFirst && System.currentTimeMillis() < deadline) {
            val seen = revision
            while (revision == seen && System.currentTimeMillis() < deadline) delay(POLL_STEP)
            delta = synchronized(lock) { syncDelta(since, limit) }
        }

        return synchronized(lock) {
            val pos = nextPosition
            nextPosition += 1
            positions[pos] = delta.snapshot
            positions.keys.removeAll { it <= pos - KEPT_POSITIONS }

            val response = delta.response
            response["pos"] = pos.toString()
            body["txn_id"]?.let { response["txn_id"] = it }
            val visible = rooms.values.count { membership(me, it.id) in setOf("join", "invite") }
            response["lists"] = body["lists"].obj().orEmpty().keys.associateWithTo(json()) { json("count" to visible) }
            ok(response)
        }
    }

    /**
     * Самая большая лента, о которой просили, и не меньше десяти: комната с одним событием и
     * токеном истории — верно, но это чат, который открывается спиннером. Демо — чтобы смотреть.
     */
    private fun timelineLimit(body: JSON): Int {
        val limits = mutableListOf<Int>()
        body["lists"].obj()?.values?.forEach { list -> list.obj()?.get("timeline_limit").int()?.let(limits::add) }
        body["room_subscriptions"].obj()?.values?.forEach { sub -> sub.obj()?.get("timeline_limit").int()?.let(limits::add) }
        return (limits.maxOrNull() ?: MIN_TIMELINE).coerceIn(MIN_TIMELINE, MAX_TIMELINE)
    }

    private class Delta(val response: JSON, val snapshot: Snapshot, val changed: Boolean)

    /** Какие комнаты отправить, в каком состоянии они оставят соединение и сдвинулось ли что-то. */
    private fun syncDelta(since: Snapshot, limit: Int): Delta {
        val snapshot = since.copy()
        val roomsOut = json()
        val roomData = json()
        val receipts = json()
        val typing = json()

        for (room in rooms.values) {
            val membership = membership(me, room.id)
            val entry: JSON? = when {
                membership == "invite" && room.id !in since.invited -> {
                    snapshot.invited += room.id
                    invitation(room)
                }
                membership == "join" && room.id !in since.joined -> {
                    snapshot.joined += room.id
                    snapshot.invited -= room.id
                    initialRoom(room, limit)
                }
                membership in setOf("join", "leave", "ban") &&
                    (room.id in since.joined || room.id in since.invited) &&
                    room.revision > (since.revisions[room.id] ?: -1) -> {
                    if (membership != "join") {
                        snapshot.joined -= room.id
                        snapshot.invited -= room.id
                    }
                    updatedRoom(room, since.delivered[room.id] ?: room.events.size)
                }
                else -> null
            } ?: continue

            roomsOut[room.id] = entry
            snapshot.delivered[room.id] = room.events.size
            snapshot.revisions[room.id] = room.revision
            if (room.accountData.isNotEmpty()) {
                roomData[room.id] = room.accountData.map { (type, content) -> json("type" to type, "content" to content) }
            }
            receipts[room.id] = receiptEvent(room)
            typing[room.id] = json("type" to "m.typing", "content" to json("user_ids" to room.typing.toList()))
        }

        val extensions = json(
            "to_device" to json("next_batch" to "0", "events" to listOf<Any>()),
            "e2ee" to json(
                "device_one_time_keys_count" to json("signed_curve25519" to oneTimeKeys),
                "device_unused_fallback_key_types" to listOf("signed_curve25519"),
                "device_lists" to json("changed" to listOf<Any>(), "left" to listOf<Any>()),
            ),
            "receipts" to json("rooms" to receipts),
            "typing" to json("rooms" to typing),
        )
        val accountData = json("rooms" to roomData)
        val accountDataChanged = since.accountData < accountDataRevision
        if (accountDataChanged) {
            accountData["global"] = globalAccountData.map { (type, content) -> json("type" to type, "content" to content) }
            snapshot.accountData = accountDataRevision
        }
        extensions["account_data"] = accountData

        return Delta(json("rooms" to roomsOut, "extensions" to extensions), snapshot, roomsOut.isNotEmpty() || accountDataChanged)
    }

    private fun initialRoom(room: MockRoom, limit: Int): JSON {
        val start = maxOf(0, room.events.size - limit)
        val entry = summary(room)
        entry["initial"] = true
        entry["required_state"] = room.state.values.toList()
        entry["timeline"] = room.events.subList(start, room.events.size).toList()
        entry["limited"] = start > 0
        entry["prev_batch"] = "t$start"
        entry["num_live"] = 0
        return entry
    }

    private fun updatedRoom(room: MockRoom, delivered: Int): JSON {
        val entry = summary(room)
        val fresh = room.events.subList(minOf(delivered, room.events.size), room.events.size).toList()
        entry["timeline"] = fresh
        entry["limited"] = false
        entry["num_live"] = room.events.size - delivered
        // Изменившееся состояние, как шлёт настоящий сервер: упрощённый sliding sync несёт состояние в
        // `required_state`, и SDK читает его оттуда, а не из ленты.
        val changed = fresh.filter { it.containsKey("state_key") }.mapNotNull { it["event_id"].str() }.toSet()
        entry["required_state"] = room.state.values.filter { it["event_id"].str() in changed }
        return entry
    }

    private fun invitation(room: MockRoom): JSON {
        val wanted = setOf("m.room.create", "m.room.name", "m.room.avatar", "m.room.topic", "m.room.join_rules", "m.room.canonical_alias")
        val stripped = room.state.values
            .filter { it["type"].str() in wanted }
            .map { json("type" to it["type"], "state_key" to it["state_key"], "content" to it["content"], "sender" to it["sender"]) }
            .toMutableList<Any?>()
        room.state["m.room.member\u0000$me"]?.let { stripped += it }
        val entry = json("initial" to true, "invite_state" to stripped, "timeline" to listOf<Any>(), "bump_stamp" to bumpStamp(room))
        room.state["m.room.name\u0000"]?.get("content").obj()?.get("name")?.let { entry["name"] = it }
        return entry
    }

    private fun summary(room: MockRoom): JSON {
        val members = room.state.values.filter { it["type"] == "m.room.member" }
        fun membershipOf(event: JSON) = event["content"].obj()?.get("membership").str()
        val joined = members.count { membershipOf(it) == "join" }
        val invited = members.count { membershipOf(it) == "invite" }
        val heroes = members
            .filter { it["state_key"] != me && membershipOf(it) in setOf("join", "invite") }
            .take(MAX_HEROES)
            .map { event ->
                val content = event["content"].obj().orEmpty()
                val hero = json("user_id" to (event["state_key"] ?: ""))
                content["displayname"]?.let { hero["displayname"] = it }
                content["avatar_url"]?.let { hero["avatar_url"] = it }
                hero
            }
        val (notifications, highlights) = unreadCounts(room)

        val entry = json(
            "joined_count" to joined, "invited_count" to invited,
            "notification_count" to notifications, "highlight_count" to highlights,
            "heroes" to heroes, "bump_stamp" to bumpStamp(room),
        )
        room.state["m.room.name\u0000"]?.get("content").obj()?.get("name")?.let { entry["name"] = it }
        room.state["m.room.avatar\u0000"]?.get("content").obj()?.get("url")?.let { entry["avatar"] = it }
        return entry
    }

    private fun bumpStamp(room: MockRoom): Long {
        val bumping = setOf("m.room.message", "m.room.create", "m.room.encrypted", "m.sticker", "org.matrix.msc3381.poll.start")
        return room.events.lastOrNull { it["type"].str() in bumping }?.get("origin_server_ts").long() ?: 0
    }

    /** Сообщения других людей после последнего прочитанного этим аккаунтом. */
    private fun unreadCounts(room: MockRoom): Pair<Int, Int> {
        val readIndex = room.receipts[me]?.let { receipt -> room.events.indexOfFirst { it["event_id"] == receipt.eventId } } ?: -1
        var notifications = 0
        var highlights = 0
        for (event in room.events.drop(readIndex + 1)) {
            if (event["sender"] == me || event["type"] != "m.room.message") continue
            notifications += 1
            val mentions = event["content"].obj()?.get("m.mentions").obj()?.get("user_ids").list()
            if (mentions?.contains(me) == true) highlights += 1
        }
        return notifications to highlights
    }

    private fun receiptEvent(room: MockRoom): JSON {
        val content = json()
        for ((user, receipt) in room.receipts) {
            val byType = content.getOrPut(receipt.eventId) { json() }.obj()!!
            val users = byType.getOrPut("m.read") { json() }.obj()!!
            users[user] = json("ts" to receipt.ts)
        }
        return json("type" to "m.receipt", "content" to content)
    }

    // MARK: Перемены в мире (под замком)

    fun membership(user: String, roomId: String): String? =
        rooms[roomId]?.state?.get("m.room.member\u0000$user")?.get("content").obj()?.get("membership").str()

    /** Добавляет событие, как сделал бы сервер: ID, время, для состояния — новое состояние. */
    private fun append(
        roomId: String,
        sender: String,
        type: String,
        content: JSON,
        stateKey: String? = null,
        transactionId: String? = null,
        extra: JSON = json(),
    ): String {
        val eventId = "$" + UUID.randomUUID().toString().replace("-", "").take(EVENT_ID_LENGTH)
        val now = System.currentTimeMillis()
        val event = json(
            "event_id" to eventId, "type" to type, "sender" to sender, "room_id" to roomId,
            "origin_server_ts" to now, "content" to content,
            "unsigned" to (transactionId?.let { json("transaction_id" to it) } ?: json()),
        )
        if (stateKey != null) event["state_key"] = stateKey
        event.putAll(extra)
        val room = rooms[roomId] ?: return eventId
        room.events += event
        if (stateKey != null) room.state["$type\u0000$stateKey"] = event
        // Своё сообщение — прочитанное.
        if (sender == me) room.receipts[me] = MockRoom.Receipt(eventId, now)
        touch(roomId)
        return eventId
    }

    private fun touch(roomId: String) {
        rooms[roomId]?.let { it.revision += 1 }
        revision += 1
    }

    private fun touchAccountData() {
        accountDataRevision += 1
        revision += 1
    }

    private fun sendMessage(roomId: String, type: String, txn: String, content: JSON): JSON {
        val key = "$roomId/$txn"
        sentTransactions[key]?.let { return json("event_id" to it) }
        val eventId = append(roomId, me, type, content, transactionId = txn)
        sentTransactions[key] = eventId
        if (type == "m.room.message" || type == "m.room.encrypted") scheduleReply(roomId, eventId, threadRoot(content))
        return json("event_id" to eventId)
    }

    private fun threadRoot(content: JSON): String? {
        val relation = content["m.relates_to"].obj() ?: return null
        return if (relation["rel_type"] == "m.thread") relation["event_id"].str() else null
    }

    /**
     * Кто-нибудь отвечает. Не умно — смысл в том, чтобы увидеть, как «печатает…» приходит и уходит,
     * квитанция встаёт на твоё сообщение и приходит новое: три экрана поведения, которого не видно в
     * чате, где больше никого нет.
     */
    private fun scheduleReply(roomId: String, eventId: String, thread: String?) {
        val room = rooms[roomId] ?: return
        val others = room.state.values
            .filter { it["type"] == "m.room.member" && it["state_key"] != me }
            .filter { it["content"].obj()?.get("membership").str() in setOf("join", "invite") }
            .mapNotNull { it["state_key"].str() }
        val responder = others.randomOrNull() ?: return
        val wait = replyDelay
        scope.launch {
            delay(REPLY_STARTS)
            synchronized(lock) {
                // Писали до того, как они приняли приглашение: принимают сначала, как любой.
                joinAs(responder, roomId)
                read(roomId, responder, eventId)
                setTyping(roomId, responder, true)
            }
            delay(wait)
            synchronized(lock) {
                setTyping(roomId, responder, false)
                val content = json("msgtype" to "m.text", "body" to MockWorld.replies.random())
                // Написали в треде — ответят в треде.
                if (thread != null) {
                    content["m.relates_to"] = json(
                        "rel_type" to "m.thread", "event_id" to thread, "is_falling_back" to true,
                        "m.in_reply_to" to json("event_id" to eventId),
                    )
                }
                append(roomId, responder, "m.room.message", content)
            }
        }
    }

    private fun read(roomId: String, user: String, eventId: String) {
        rooms[roomId]?.receipts?.set(user, MockRoom.Receipt(eventId, System.currentTimeMillis()))
        touch(roomId)
    }

    private fun setTyping(roomId: String, user: String, typing: Boolean) {
        rooms[roomId]?.typing?.let { if (typing) it += user else it -= user }
        touch(roomId)
    }

    /** Позванный приложением человек появляется мгновением позже, как в демо и бывает. */
    private fun acceptLater(user: String, roomId: String) {
        scope.launch {
            delay(ACCEPT_AFTER)
            synchronized(lock) { joinAs(user, roomId) }
        }
    }

    private fun joinAs(user: String, roomId: String) {
        if (membership(user, roomId) != "invite") return
        val content = memberContent(user)
        content["membership"] = "join"
        append(roomId, user, "m.room.member", content, stateKey = user)
    }

    private fun memberContent(user: String): JSON {
        val content = json("membership" to "join")
        profiles[user]?.let { profile ->
            content["displayname"] = profile.name
            profile.avatar?.let { content["avatar_url"] = it }
        }
        return content
    }

    // MARK: Комнаты

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun roomRoute(method: String, roomId: String, segments: List<String>, query: Map<String, String>, body: JSON): HttpResponse? {
        val action = segments[0]
        if (action == "join" && method == "POST") return join(roomId)
        if (action == "hierarchy") return hierarchy(roomId)
        val room = rooms[roomId] ?: throw Failure(404, "M_NOT_FOUND", "Unknown room")

        return when {
            method == "PUT" && action == "send" && segments.size >= 3 -> ok(sendMessage(roomId, segments[1], segments[2], body))

            method == "PUT" && action == "redact" && segments.size >= 3 -> {
                val key = "$roomId/${segments[2]}"
                sentTransactions[key]?.let { return ok(json("event_id" to it)) }
                val content = json("redacts" to segments[1])
                body["reason"]?.let { content["reason"] = it }
                redactInPlace(roomId, segments[1])
                val id = append(roomId, me, "m.room.redaction", content, transactionId = segments[2], extra = json("redacts" to segments[1]))
                sentTransactions[key] = id
                ok(json("event_id" to id))
            }

            method == "PUT" && action == "state" && segments.size >= 2 ->
                ok(json("event_id" to append(roomId, me, segments[1], body, stateKey = segments.getOrElse(2) { "" })))

            method == "GET" && action == "state" && segments.size == 1 -> okArray(room.state.values.toList())

            method == "GET" && action == "state" -> {
                val event = room.state["${segments[1]}\u0000${segments.getOrElse(2) { "" }}"]
                    ?: throw Failure(404, "M_NOT_FOUND", "Event not found")
                ok(event["content"].obj() ?: json())
            }

            method == "GET" && action == "members" -> {
                val excluded = query["not_membership"]
                ok(
                    json(
                        "chunk" to room.state.values.filter {
                            it["type"] == "m.room.member" && (excluded == null || it["content"].obj()?.get("membership") != excluded)
                        },
                    ),
                )
            }

            method == "GET" && action == "joined_members" -> {
                val joined = json()
                for (event in room.state.values) {
                    if (event["type"] != "m.room.member") continue
                    val content = event["content"].obj().orEmpty()
                    val user = event["state_key"].str() ?: continue
                    if (content["membership"] != "join") continue
                    joined[user] = json("display_name" to content["displayname"], "avatar_url" to content["avatar_url"])
                }
                ok(json("joined" to joined))
            }

            method == "GET" && action == "messages" -> ok(messages(roomId, query))

            method == "GET" && action == "event" && segments.size > 1 ->
                ok(room.events.firstOrNull { it["event_id"] == segments[1] } ?: throw Failure(404, "M_NOT_FOUND", "Event not found"))

            method == "GET" && action == "context" && segments.size > 1 ->
                ok(context(roomId, segments[1], query["limit"]?.toIntOrNull() ?: DEFAULT_CONTEXT))

            method == "GET" && action == "relations" && segments.size > 1 -> {
                val related = room.events.filter {
                    val relation = it["content"].obj()?.get("m.relates_to").obj()
                    relation?.get("event_id") == segments[1] && (segments.size < 3 || relation["rel_type"] == segments[2])
                }
                ok(json("chunk" to related.reversed()))
            }

            method == "GET" && action == "threads" -> ok(json("chunk" to listOf<Any>()))

            method == "POST" && action == "receipt" && segments.size >= 3 -> {
                read(roomId, me, segments[2])
                ok(json())
            }

            method == "POST" && action == "read_markers" -> {
                (body["m.read"] ?: body["m.read.private"]).str()?.let {
                    room.receipts[me] = MockRoom.Receipt(it, System.currentTimeMillis())
                }
                body["m.fully_read"].str()?.let { room.accountData["m.fully_read"] = json("event_id" to it) }
                touch(roomId)
                ok(json())
            }

            method == "PUT" && action == "typing" -> ok(json())

            method == "POST" && action == "leave" -> {
                if (membership(me, roomId) != "leave") append(roomId, me, "m.room.member", json("membership" to "leave"), stateKey = me)
                ok(json())
            }

            method == "POST" && action == "forget" -> ok(json())

            method == "POST" && action == "invite" -> {
                val user = body["user_id"].str() ?: throw Failure(400, "M_MISSING_PARAM")
                invite(user, roomId)
                ok(json())
            }

            method == "POST" && action in setOf("kick", "ban", "unban") -> {
                val user = body["user_id"].str() ?: throw Failure(400, "M_MISSING_PARAM")
                val content = json("membership" to if (action == "ban") "ban" else "leave")
                body["reason"]?.let { content["reason"] = it }
                append(roomId, me, "m.room.member", content, stateKey = user)
                ok(json())
            }

            method == "POST" && action == "report" -> ok(json())
            method == "GET" && action == "aliases" -> ok(json("aliases" to listOf<Any>()))

            method == "GET" && action == "timestamp_to_event" -> {
                val ts = query["ts"]?.toLongOrNull() ?: 0
                val hit = if (query["dir"] == "b") {
                    room.events.lastOrNull { (it["origin_server_ts"].long() ?: 0) <= ts }
                } else {
                    room.events.firstOrNull { (it["origin_server_ts"].long() ?: 0) >= ts }
                } ?: throw Failure(404, "M_NOT_FOUND")
                ok(json("event_id" to hit["event_id"], "origin_server_ts" to hit["origin_server_ts"]))
            }

            else -> null
        }
    }

    /** История: старые первыми в токене, новые первыми в куске — как отдаёт `/messages`. */
    private fun messages(roomId: String, query: Map<String, String>): JSON {
        val events = rooms[roomId]?.events ?: return json("chunk" to listOf<Any>(), "state" to listOf<Any>())
        val limit = minOf(MAX_PAGE, query["limit"]?.toIntOrNull() ?: DEFAULT_PAGE)
        val from = query["from"]?.drop(1)?.toIntOrNull() ?: events.size
        val clamped = from.coerceIn(0, events.size)

        if (query["dir"] == "f") {
            val end = minOf(events.size, clamped + limit)
            return json("chunk" to events.subList(clamped, end).toList(), "start" to "t$clamped", "end" to "t$end", "state" to listOf<Any>())
        }
        val start = maxOf(0, clamped - limit)
        val response = json("chunk" to events.subList(start, clamped).reversed(), "start" to "t$clamped", "state" to listOf<Any>())
        if (start > 0) response["end"] = "t$start"
        return response
    }

    private fun context(roomId: String, eventId: String, limit: Int): JSON {
        val room = rooms[roomId] ?: throw Failure(404, "M_NOT_FOUND", "Unknown room")
        val events = room.events
        val index = events.indexOfFirst { it["event_id"] == eventId }
        if (index < 0) throw Failure(404, "M_NOT_FOUND", "Event not found")
        val before = maxOf(0, index - limit / 2)
        val after = minOf(events.size, index + 1 + limit / 2)
        return json(
            "event" to events[index],
            "events_before" to events.subList(before, index).reversed(),
            "events_after" to events.subList(index + 1, after).toList(),
            "start" to "t$before", "end" to "t$after",
            "state" to room.state.values.toList(),
        )
    }

    private fun redactInPlace(roomId: String, eventId: String) {
        val events = rooms[roomId]?.events ?: return
        val index = events.indexOfFirst { it["event_id"] == eventId }
        if (index < 0) return
        val copy = events[index].copy()
        copy["content"] = json()
        copy["unsigned"] = json(
            "redacted_because" to json("type" to "m.room.redaction", "sender" to me, "content" to json("redacts" to eventId)),
        )
        events[index] = copy
    }

    private fun invite(user: String, roomId: String) {
        if (membership(user, roomId) == "join") return
        val content = memberContent(user)
        content["membership"] = "invite"
        append(roomId, me, "m.room.member", content, stateKey = user)
        acceptLater(user, roomId)
    }

    private fun join(roomIdOrAlias: String): HttpResponse {
        if (rooms[roomIdOrAlias] == null) throw Failure(404, "M_NOT_FOUND", "No such room")
        if (membership(me, roomIdOrAlias) != "join") append(roomIdOrAlias, me, "m.room.member", memberContent(me), stateKey = me)
        return ok(json("room_id" to roomIdOrAlias))
    }

    private fun createRoom(body: JSON): HttpResponse {
        val id = "!" + UUID.randomUUID().toString().take(ROOM_ID_LENGTH).lowercase() + ":" + SERVER_NAME
        rooms[id] = MockRoom(id)
        val creation = body["creation_content"].obj()?.copy() ?: json()
        creation["room_version"] = "11"
        append(id, me, "m.room.create", creation, stateKey = "")
        append(id, me, "m.room.member", memberContent(me), stateKey = me)

        val power = MockWorld.powerLevels(listOf(me))
        body["power_level_content_override"].obj()?.let(power::putAll)
        append(id, me, "m.room.power_levels", power, stateKey = "")

        val isPublic = body["preset"] == "public_chat" || body["visibility"] == "public"
        append(id, me, "m.room.join_rules", json("join_rule" to if (isPublic) "public" else "invite"), stateKey = "")
        append(id, me, "m.room.history_visibility", json("history_visibility" to "shared"), stateKey = "")
        body["name"].str()?.takeIf { it.isNotEmpty() }?.let { append(id, me, "m.room.name", json("name" to it), stateKey = "") }
        body["topic"].str()?.takeIf { it.isNotEmpty() }?.let { append(id, me, "m.room.topic", json("topic" to it), stateKey = "") }
        for (state in body["initial_state"].list().orEmpty()) {
            val event = state.obj() ?: continue
            val type = event["type"].str() ?: continue
            append(id, me, type, event["content"].obj() ?: json(), stateKey = event["state_key"].str() ?: "")
        }
        for (user in body["invite"].list().orEmpty().filterIsInstance<String>()) {
            val content = memberContent(user)
            content["membership"] = "invite"
            if (body["is_direct"] == true) content["is_direct"] = true
            append(id, me, "m.room.member", content, stateKey = user)
            acceptLater(user, id)
        }
        if (isPublic) publicRoomIds += id
        return ok(json("room_id" to id))
    }

    private fun hierarchy(spaceId: String): HttpResponse {
        val space = rooms[spaceId] ?: throw Failure(404, "M_NOT_FOUND")
        val children = space.state.values.filter { it["type"] == "m.space.child" }
        val chunk = mutableListOf(publishedRoom(space, children))
        for (child in children) {
            rooms[child["state_key"].str()]?.let { chunk += publishedRoom(it) }
        }
        return ok(json("rooms" to chunk))
    }

    private fun publishedRoom(room: MockRoom, children: List<JSON> = emptyList()): JSON {
        fun state(type: String, key: String) = room.state["$type\u0000"]?.get("content").obj()?.get(key)
        val joined = room.state.values.count { it["type"] == "m.room.member" && it["content"].obj()?.get("membership") == "join" }
        val entry = json(
            "room_id" to room.id, "num_joined_members" to joined, "world_readable" to false, "guest_can_join" to false,
            "join_rule" to (state("m.room.join_rules", "join_rule") ?: "invite"),
            "children_state" to children.map {
                json(
                    "type" to "m.space.child", "state_key" to it["state_key"], "content" to it["content"],
                    "sender" to it["sender"], "origin_server_ts" to it["origin_server_ts"],
                )
            },
        )
        state("m.room.name", "name")?.let { entry["name"] = it }
        state("m.room.topic", "topic")?.let { entry["topic"] = it }
        state("m.room.avatar", "url")?.let { entry["avatar_url"] = it }
        state("m.room.create", "type")?.let { entry["room_type"] = it }
        return entry
    }

    private fun publicRooms(): HttpResponse {
        val chunk = publicRoomIds.mapNotNull { rooms[it] }.map { publishedRoom(it) }
        return ok(json("chunk" to chunk, "total_room_count_estimate" to chunk.size))
    }

    private fun searchDirectory(body: JSON): HttpResponse {
        val term = body["search_term"].str().orEmpty().lowercase()
        val results = profiles.entries
            .filter { it.key != me }
            .filter { term.isEmpty() || it.key.lowercase().contains(term) || it.value.name.lowercase().contains(term) }
            .sortedBy { it.value.name }
            .map { (user, profile) ->
                val result = json("user_id" to user, "display_name" to profile.name)
                profile.avatar?.let { result["avatar_url"] = it }
                result
            }
        return ok(json("results" to results, "limited" to false))
    }

    // MARK: Каталог адресов

    private fun directory(method: String, rest: List<String>, body: JSON): HttpResponse {
        if (rest.size == 2 && rest[0] == "room") {
            val alias = rest[1]
            when (method) {
                "GET" -> return aliases[alias]?.let { ok(json("room_id" to it, "servers" to listOf(SERVER_NAME))) }
                    ?: error(404, "M_NOT_FOUND", "Room alias not found")
                "PUT" -> {
                    if (aliases.containsKey(alias)) return error(409, "M_UNKNOWN", "Room alias already exists")
                    aliases[alias] = body["room_id"].str().orEmpty()
                    return ok(json())
                }
                "DELETE" -> return if (aliases.remove(alias) != null) ok(json()) else error(404, "M_NOT_FOUND")
            }
        }
        if (rest.size == 3 && rest[0] == "list" && rest[1] == "room") {
            val id = rest[2]
            if (method == "GET") return ok(json("visibility" to if (id in publicRoomIds) "public" else "private"))
            if (method == "PUT") {
                publicRoomIds.removeAll { it == id }
                if (body["visibility"] == "public") publicRoomIds += id
                revision += 1
                return ok(json())
            }
        }
        return error(404, "M_UNRECOGNIZED")
    }

    // MARK: Профили и account data

    private fun profileRoute(method: String, segments: List<String>, body: JSON): HttpResponse {
        val user = segments[1]
        val profile = profiles[user] ?: throw Failure(404, "M_NOT_FOUND", "Profile not found")

        if (method == "PUT" && segments.size > 2 && user == me) {
            if (segments[2] == "displayname") profile.name = body["displayname"].str() ?: profile.name
            if (segments[2] == "avatar_url") profile.avatar = body["avatar_url"].str()?.takeIf { it.isNotEmpty() }
            // Новое имя — новость в каждой комнате, где ты есть, как на настоящем сервере.
            for (id in rooms.keys.toList()) {
                if (membership(me, id) == "join") append(id, me, "m.room.member", memberContent(me), stateKey = me)
            }
            return ok(json())
        }

        val answer = json()
        if (segments.size < 3 || segments[2] == "displayname") answer["displayname"] = profile.name
        if (segments.size < 3 || segments[2] == "avatar_url") profile.avatar?.let { answer["avatar_url"] = it }
        return ok(answer)
    }

    /** `/user/{me}/…`: account data, данные комнат, теги и фильтры. */
    private fun userRoute(method: String, segments: List<String>, body: JSON): HttpResponse? {
        val head = segments.firstOrNull().orEmpty()
        return when {
            method == "POST" && head == "filter" -> ok(json("filter_id" to "1"))
            method == "GET" && head == "account_data" && segments.size > 1 ->
                ok(globalAccountData[segments[1]] ?: throw Failure(404, "M_NOT_FOUND", "Account data not found"))
            method == "PUT" && head == "account_data" && segments.size > 1 -> {
                globalAccountData[segments[1]] = body
                touchAccountData()
                ok(json())
            }
            head == "rooms" && segments.size >= 3 -> {
                val roomId = segments[1]
                val room = rooms[roomId] ?: throw Failure(404, "M_NOT_FOUND")
                when {
                    segments[2] == "tags" -> {
                        val tags = room.accountData["m.tag"]?.get("tags").obj()?.copy() ?: json()
                        when {
                            method == "GET" -> return ok(json("tags" to tags))
                            method == "PUT" && segments.size > 3 -> tags[segments[3]] = body
                            method == "DELETE" && segments.size > 3 -> tags.remove(segments[3])
                        }
                        room.accountData["m.tag"] = json("tags" to tags)
                        touch(roomId)
                        ok(json())
                    }
                    segments[2] == "account_data" && segments.size > 3 -> {
                        if (method == "PUT") {
                            room.accountData[segments[3]] = body
                            touch(roomId)
                            ok(json())
                        } else {
                            ok(room.accountData[segments[3]] ?: throw Failure(404, "M_NOT_FOUND"))
                        }
                    }
                    else -> null
                }
            }
            method == "POST" && head == "openid" ->
                ok(json("access_token" to "demo", "token_type" to "Bearer", "matrix_server_name" to SERVER_NAME, "expires_in" to 3600))
            else -> null
        }
    }

    /** Правила уведомлений живут в account data, как на настоящем сервере: перемена возвращается синхронизацией. */
    private fun pushRules(method: String, segments: List<String>, body: JSON): HttpResponse {
        val rules = globalAccountData["m.push_rules"] ?: MockWorld.pushRules(me)
        if (segments.size < 3) return ok(rules)
        val kind = segments[1]
        val ruleId = segments[2]
        val global = rules["global"].obj()?.copy() ?: json()
        val list = global[kind].list().orEmpty().mapNotNull { it.obj() }.toMutableList()
        val index = list.indexOfFirst { it["rule_id"] == ruleId }.takeIf { it >= 0 }

        when {
            method == "GET" -> {
                if (index == null) throw Failure(404, "M_NOT_FOUND")
                return if (segments.size > 3) ok(json(segments[3] to list[index][segments[3]])) else ok(list[index])
            }
            method == "DELETE" -> if (index != null) list.removeAt(index)
            method == "PUT" && segments.size > 3 -> {
                if (index == null) throw Failure(404, "M_NOT_FOUND")
                val rule = list[index].copy()
                rule[segments[3]] = body[segments[3]]
                list[index] = rule
            }
            method == "PUT" -> {
                val rule = body.copy()
                rule["rule_id"] = ruleId
                rule["default"] = false
                rule["enabled"] = true
                if (kind == "room" || kind == "sender") rule.remove("conditions")
                if (index != null) list[index] = rule else list.add(0, rule)
            }
        }
        global[kind] = list
        val updated = rules.copy()
        updated["global"] = global
        globalAccountData["m.push_rules"] = updated
        touchAccountData()
        return ok(json())
    }

    private fun deviceDescription(): JSON = json(
        "device_id" to deviceId, "display_name" to "Iskra (демо)",
        "last_seen_ts" to System.currentTimeMillis(), "last_seen_ip" to "127.0.0.1",
    )

    // MARK: Ключи

    private fun keysRoute(segments: List<String>, body: JSON): HttpResponse? = when (segments.firstOrNull().orEmpty()) {
        "upload" -> {
            body["device_keys"].obj()?.let { deviceKeys = it }
            oneTimeKeys += body["one_time_keys"].obj()?.size ?: 0
            ok(json("one_time_key_counts" to json("signed_curve25519" to oneTimeKeys)))
        }
        "query" -> {
            val asked = body["device_keys"].obj()?.keys.orEmpty()
            val devices = json()
            for (user in asked) devices[user] = if (user == me) deviceKeys?.let { json(deviceId to it) } ?: json() else json()
            val answer = json("device_keys" to devices, "failures" to json())
            if (me in asked) {
                for ((kind, field) in listOf("master" to "master_keys", "self_signing" to "self_signing_keys", "user_signing" to "user_signing_keys")) {
                    crossSigning[kind]?.let { answer[field] = json(me to it) }
                }
            }
            ok(answer)
        }
        "claim" -> ok(json("one_time_keys" to json(), "failures" to json()))
        "changes" -> ok(json("changed" to listOf<Any>(), "left" to listOf<Any>()))
        "device_signing" -> {
            for (kind in listOf("master", "self_signing", "user_signing")) body["${kind}_key"].obj()?.let { crossSigning[kind] = it }
            ok(json())
        }
        "signatures" -> {
            for ((keyId, signed) in body[me].obj().orEmpty()) {
                val signedObject = signed.obj() ?: continue
                val existing = deviceKeys
                // «master» — имя корневого ключа cross-signing в спецификации Matrix.
                val master = crossSigning["master"]
                if (keyId == deviceId && existing != null) {
                    deviceKeys = mergeSignatures(existing, signedObject)
                } else if (master != null && master["keys"].obj()?.containsKey("ed25519:$keyId") == true) {
                    crossSigning["master"] = mergeSignatures(master, signedObject)
                }
            }
            ok(json("failures" to json()))
        }
        else -> null
    }

    private fun mergeSignatures(target: JSON, source: JSON): JSON {
        val result = target.copy()
        val all = target["signatures"].obj()?.copy() ?: json()
        for ((user, signatures) in source["signatures"].obj().orEmpty()) {
            val existing = all[user].obj()?.copy() ?: json()
            signatures.obj()?.let(existing::putAll)
            all[user] = existing
        }
        result["signatures"] = all
        return result
    }

    private fun roomKeysRoute(method: String, segments: List<String>, body: JSON): HttpResponse? {
        val head = segments.firstOrNull().orEmpty()
        return when {
            method == "GET" && head == "version" -> {
                val current = backup?.copy() ?: throw Failure(404, "M_NOT_FOUND", "No current backup version")
                current["count"] = backupKeys
                current["etag"] = backupKeys.toString()
                ok(current)
            }
            method == "POST" && head == "version" -> {
                val version = ((backup?.get("version").str()?.toIntOrNull() ?: 0) + 1).toString()
                backup = json("algorithm" to (body["algorithm"] ?: ""), "auth_data" to (body["auth_data"] ?: json()), "version" to version)
                backupKeys = 0
                ok(json("version" to version))
            }
            method == "PUT" && head == "version" -> {
                backup?.set("auth_data", body["auth_data"])
                ok(json())
            }
            method == "DELETE" && head == "version" -> {
                backup = null
                ok(json())
            }
            method == "PUT" && head == "keys" -> {
                for (room in body["rooms"].obj().orEmpty().values) backupKeys += room.obj()?.get("sessions").obj()?.size ?: 0
                ok(json("count" to backupKeys, "etag" to backupKeys.toString()))
            }
            method == "GET" && head == "keys" -> ok(json("rooms" to json()))
            else -> null
        }
    }

    // MARK: Медиа

    private fun mediaRoute(method: String, segments: List<String>, raw: HttpRequest): HttpResponse? {
        val head = segments.firstOrNull().orEmpty()
        return when {
            method == "GET" && head == "config" -> ok(json("m.upload.size" to UPLOAD_LIMIT))
            method == "POST" && head == "upload" -> {
                val id = UUID.randomUUID().toString().lowercase()
                media[id] = raw.body to (raw.header("Content-Type") ?: "application/octet-stream")
                ok(json("content_uri" to "mxc://$SERVER_NAME/$id"))
            }
            method == "GET" && (head == "download" || head == "thumbnail") -> {
                val file = segments.getOrNull(2)?.let { media[it] } ?: throw Failure(404, "M_NOT_FOUND", "Not found")
                HttpResponse(200, mapOf("Content-Type" to file.second), file.first)
            }
            method == "GET" && head == "preview_url" -> ok(json())
            else -> null
        }
    }

    companion object {
        const val SERVER_NAME = "iskra.demo"

        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        private const val MAX_POLL_MS = 30_000
        private const val DEFAULT_POLL_MS = 5_000
        private val POLL_STEP = 100.milliseconds
        private val HELD_SYNC = 600.seconds
        private const val KEPT_POSITIONS = 64
        private const val MIN_TIMELINE = 10
        private const val MAX_TIMELINE = 50
        private const val MAX_HEROES = 5
        private const val EVENT_ID_LENGTH = 24
        private const val ROOM_ID_LENGTH = 12
        private const val MAX_PAGE = 100
        private const val DEFAULT_PAGE = 10
        private const val DEFAULT_CONTEXT = 10
        private const val UPLOAD_LIMIT = 100 * 1024 * 1024
        private val REPLY_STARTS = 800.milliseconds
        private val ACCEPT_AFTER = 1500.milliseconds

        val versions: JSON
            get() = json(
                "versions" to listOf("r0.6.1", "v1.1", "v1.2", "v1.3", "v1.4", "v1.5", "v1.6", "v1.7", "v1.8", "v1.9", "v1.10", "v1.11", "v1.12"),
                "unstable_features" to json("org.matrix.simplified_msc3575" to true, "org.matrix.msc3916.stable" to true),
            )

        fun split(target: String): Pair<List<String>, Map<String, String>> {
            val pathPart = target.substringBefore('?')
            val queryPart = if ('?' in target) target.substringAfter('?') else ""
            var path = pathPart.split('/').drop(1).map(::decode)
            // Завершающий слэш — пустой последний сегмент. Отброшен: там, где он что-то значит
            // (пустой ключ состояния в `state/m.room.name/`), отсутствующий ключ и так читается пустым.
            if (path.lastOrNull()?.isEmpty() == true) path = path.dropLast(1)
            val query = linkedMapOf<String, String>()
            if (queryPart.isNotEmpty()) {
                for (pair in queryPart.split('&')) {
                    val key = decode(pair.substringBefore('='))
                    query[key] = if ('=' in pair) decode(pair.substringAfter('=')) else ""
                }
            }
            return path to query
        }

        /** Процент-декодирование без превращения «+» в пробел: в пути Matrix «+» — это «+». */
        private fun decode(text: String): String = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)

        private fun body(value: Any?) = JsonValues.encode(value).toByteArray()

        fun ok(body: JSON) = HttpResponse(200, mapOf("Content-Type" to "application/json"), body(body))

        fun okArray(body: List<JSON>) = HttpResponse(200, mapOf("Content-Type" to "application/json"), body(body))

        fun error(status: Int, code: String, message: String = "") =
            HttpResponse(status, mapOf("Content-Type" to "application/json"), body(json("errcode" to code, "error" to message)))
    }
}
