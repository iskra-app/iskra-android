package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.PublicRoomJoinRule
import org.matrix.rustcomponents.sdk.RoomDescription
import org.matrix.rustcomponents.sdk.RoomDirectorySearch
import org.matrix.rustcomponents.sdk.RoomDirectorySearchEntriesListener
import org.matrix.rustcomponents.sdk.RoomDirectorySearchEntryUpdate
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomPreviewInfo
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.TaskHandle
import wtf.iskra.core.R
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Кто-то, о ком знает каталог пользователей сервера: строка в «Новом чате», ещё не разговор. Порт `PersonProfile`. */
data class PersonProfile(val id: String, val displayName: String?, val avatarUrl: String?) {
    /** Как его звать: имя, что он себе дал, а не дал — его адрес. */
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: id
}

/** Комната из публичного каталога сервера. Порт `PublicRoom`. */
data class PublicRoom(
    val id: String,
    val name: String?,
    val topic: String?,
    /** `#имя:сервер`, если есть: по нему комнату находят снова. */
    val alias: String?,
    val avatarUrl: String?,
    val memberCount: Int,
    /**
     * Войти может любой. Остальное в каталоге — комнаты, что показывают себя и просят постучаться или позвать: они
     * видны, потому что они есть, но нажатием не открываются, и строка говорит об этом, а не падает на нажатии.
     */
    val isOpen: Boolean,
) {
    /** Название, адрес или ID — по порядку: строка каталога с пустотой на месте имени никому не нужна. */
    val title: String get() = name?.takeIf { it.isNotEmpty() } ?: alias ?: id

    internal constructor(description: RoomDescription) : this(
        id = description.roomId,
        name = description.name,
        topic = description.topic?.trim(),
        alias = description.alias,
        avatarUrl = description.avatarUrl,
        memberCount = description.joinedMembers.toInt(),
        // Ни одного правила входа — так старые серверы показывают открытую комнату.
        isOpen = description.joinRule == null || description.joinRule == PublicRoomJoinRule.PUBLIC,
    )

    internal constructor(preview: RoomPreviewInfo) : this(
        id = preview.roomId,
        name = preview.name,
        topic = preview.topic?.trim(),
        alias = preview.canonicalAlias,
        avatarUrl = preview.avatarUrl,
        memberCount = preview.numJoinedMembers.toInt(),
        isOpen = preview.joinRule == null || preview.joinRule == org.matrix.rustcomponents.sdk.JoinRule.Public,
    )
}

/**
 * Что не вышло при создании комнаты или входе в неё. Опечатка — не то же, что отказ сервера: поправить человек может
 * только одно из них.
 */
enum class ConversationFailure(val message: Int) {
    /** Matrix ID того, кого зовут, — не Matrix ID. */
    BAD_INVITEE(R.string.room_action_invite_failed),
    CREATE_REFUSED(R.string.room_action_create_failed),

    /** Не `#имя:сервер` и не `!id:сервер`. */
    BAD_ADDRESS(R.string.room_action_join_address_invalid),

    /** Нет такой комнаты, она закрыта, или сервер не ответил. */
    JOIN_REFUSED(R.string.room_action_join_failed),
}

class ConversationException(val failure: ConversationFailure) : Exception(failure.name)

/** Человек, с которым у аккаунта уже есть личный чат. */
data class Contact(val id: String, val name: String, val avatarUrl: String?, val roomId: String) {
    companion object {
        /**
         * Все, с кем есть личный чат, по разу. `avatarSeed` личного чата — Matrix ID собеседника, а когда SDK не знает, кто
         * он, — ID самой комнаты; в тех комнатах некого перечислять. Два чата с одним человеком (так бывает: клиент не
         * нашёл первый) — одна строка, из более свежего, потому что список идёт по свежести.
         */
        fun of(rooms: List<RoomSummary>): List<Contact> {
            val seen = HashSet<String>()
            return rooms.mapNotNull { room ->
                if (!room.isDirectMessage || room.isInvitation || !room.avatarSeed.startsWith("@") || !seen.add(room.avatarSeed)) {
                    null
                } else {
                    Contact(room.avatarSeed, room.name, room.avatarUrl, room.id)
                }
            }
        }

        /**
         * Под первыми буквами, как читают записную книжку. Что не начинается с буквы — эмодзи, цифра — под «#», последним.
         */
        fun byLetter(contacts: List<Contact>): List<Pair<String, List<Contact>>> {
            val order = compareBy<Contact, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            return contacts
                .groupBy { contact ->
                    val first = contact.name.firstOrNull()
                    if (first != null && first.isLetter()) first.uppercase() else "#"
                }
                .map { (letter, people) -> letter to people.sortedWith(order) }
                .sortedWith { left, right ->
                    when {
                        left.first == "#" -> if (right.first == "#") 0 else 1
                        right.first == "#" -> -1
                        else -> left.first.compareTo(right.first, ignoreCase = true)
                    }
                }
        }
    }
}

/**
 * Новый разговор: личный чат, комната, вход по адресу и каталог сервера. Порт `RoomCreation` и `Directory`.
 *
 * Принадлежит сеансу, а не экрану: у экрана нет ничего, кроме поля и нажатия.
 */
class Conversations internal constructor(
    private val client: Client,
    private val ownUserId: String,
    private val scope: CoroutineScope,
) {
    /**
     * Спрашивает каталог пользователей сервера, кто откликается на [term]. Что каталог покрывает — решает сервер: по
     * умолчанию это те, кто делит с тобой комнату или сидит в публичной. Полный Matrix ID, которого каталог не знает, —
     * всё равно тот, кому можно написать; эту строку добавляет вызывающий, потому что только он знает, что набрано.
     */
    suspend fun searchPeople(term: String, limit: Long = 30): List<PersonProfile> {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return emptyList()
        val found = withTimeLimit(DIRECTORY_LIMIT) { client.searchUsers(trimmed, limit.toULong()) }
        return found.results
            .filter { it.userId != ownUserId }
            .map { PersonProfile(it.userId, it.displayName, it.avatarUrl) }
    }

    /**
     * Разговор с одним человеком. Не `PRIVATE_CHAT`, а `TRUSTED_PRIVATE_CHAT`: тот, кого позвали, получает тот же уровень,
     * что у создателя. В комнате на двоих «владелец и гость» — различие без смысла: любой может её переименовать, и никто
     * не выгонит другого, — а разговор равных именно таков. И всегда зашифрован: личный чат — случай, где это важнее
     * всего и где никто не подумал бы это включить.
     */
    suspend fun createDirectChat(userId: String): String {
        if (!RoomModeration.isMatrixId(userId)) throw ConversationException(ConversationFailure.BAD_INVITEE)
        return create(
            CreateRoomParameters(
                name = null,
                topic = null,
                isEncrypted = true,
                isDirect = true,
                visibility = RoomVisibility.Private,
                preset = RoomPreset.TRUSTED_PRIVATE_CHAT,
                invite = listOf(userId),
            ),
        )
    }

    /**
     * Разговор, который у аккаунта с этим человеком уже есть. Спрашивается у SDK, а не ищется в списке чатов: личный чат —
     * не комната, что *выглядит* как «один на один», а комната, названная в `m.direct` аккаунта, то есть то, что любой
     * другой клиент этого аккаунта зовёт разговором с ним. Гадание по списку находило бы нужную комнату почти всегда, а
     * в остальное время тихо открывало бы чат, из которого человек вышел, или брало одну из двух.
     *
     * Считается только комната, куда ещё стоит заходить: `getDmRoom` отвечает и про ту, откуда ушли, а в чат, что
     * бросил собеседник, возвращать не надо — читать там нечего и писать некому. Тогда ответ — ничего, и вызывающий
     * заводит новый разговор.
     *
     * Без ожидания: это справка в локальном хранилище, а не запрос.
     */
    fun directChat(userId: String): String? {
        val room = runCatching { client.getDmRoom(userId) }.getOrNull() ?: return null
        return try {
            if (room.membership() == Membership.JOINED) room.id() else null
        } finally {
            room.close()
        }
    }

    /** Всё, что значит «написать ему»: тот разговор, что есть, или новый. Одним вызовом, чтобы подпись кнопки не разошлась с делом. */
    suspend fun openDirectChat(userId: String): String = directChat(userId) ?: createDirectChat(userId)

    /**
     * Создаёт комнату. Зашифрована ли она — следствие того, открыта ли, а не отдельный выключатель. Шифрованная открытая
     * комната — комната, куда чужие входят и ничего не читают: ключи раздали до них, и она выглядит сломанной для всех, кто
     * зашёл. Открытая — доска объявлений, без шифра; всё остальное — разговор, с шифром. Одно решение — словами, что
     * человек уже понимает, вместо двух, которые надо сводить.
     */
    suspend fun createRoom(name: String, topic: String?, isPublic: Boolean, invitees: List<String> = emptyList()): String {
        if (!invitees.all(RoomModeration::isMatrixId)) throw ConversationException(ConversationFailure.BAD_INVITEE)
        return create(
            CreateRoomParameters(
                name = name.trim(),
                topic = topic?.trim()?.takeIf { it.isNotEmpty() },
                isEncrypted = !isPublic,
                isDirect = false,
                visibility = if (isPublic) RoomVisibility.Public else RoomVisibility.Private,
                preset = if (isPublic) RoomPreset.PUBLIC_CHAT else RoomPreset.PRIVATE_CHAT,
                invite = invitees.takeIf { it.isNotEmpty() },
            ),
        )
    }

    /**
     * Входит по тому, что дали: `#имя:сервер` или `!id:сервер`. Сервер из адреса — тот, через кого спрашивать: для
     * псевдонима SDK разрешает его сам, а для голого ID это единственная подсказка, и без неё сервер, которого в комнате
     * нет, не у кого спросить.
     */
    suspend fun joinRoom(address: String): String {
        val trimmed = address.trim()
        if (!isRoomAddress(trimmed)) throw ConversationException(ConversationFailure.BAD_ADDRESS)
        val via = trimmed.split(':', limit = 2).drop(1).take(1)
        return join(trimmed, via)
    }

    /** Входит в комнату, на которую указала ссылка: серверы, через которые спрашивать, ей приложены ([MatrixLink.Room]). */
    suspend fun joinRoom(idOrAlias: String, via: List<String>): String {
        if (!isRoomAddress(idOrAlias)) throw ConversationException(ConversationFailure.BAD_ADDRESS)
        return join(idOrAlias, via)
    }

    /**
     * Комната, в которой аккаунт уже есть, по этому ID или псевдониму. Локально, поэтому QR комнаты, где ты состоишь, открывает её
     * сразу, и без сети, а не просит сервер впустить в ту, откуда не уходил. Null — нет такой.
     */
    suspend fun joinedRoom(idOrAlias: String): String? {
        val roomId = if (idOrAlias.startsWith("!")) {
            idOrAlias
        } else {
            val resolved = runCatching { withTimeLimit(DIRECTORY_LIMIT) { client.resolveRoomAlias(idOrAlias) } }.getOrNull() ?: return null
            resolved.roomId
        }
        val room = runCatching { client.getRoom(roomId) }.getOrNull() ?: return null
        return try {
            if (room.membership() == Membership.JOINED) room.id() else null
        } finally {
            room.close()
        }
    }

    /**
     * Что за комната, на которую указала ссылка, — прежде чем решать входить: имя, тема, сколько людей. Null, когда сервер не
     * говорит — комната закрытая или её нет; войти всё равно можно, и вызывающий предложит.
     */
    suspend fun roomPreview(idOrAlias: String, via: List<String>): PublicRoom? {
        val preview = runCatching {
            withTimeLimit(DIRECTORY_LIMIT) {
                if (idOrAlias.startsWith("#")) client.getRoomPreviewFromRoomAlias(idOrAlias) else client.getRoomPreviewFromRoomId(idOrAlias, via)
            }
        }.getOrNull() ?: return null
        return try {
            PublicRoom(preview.info())
        } finally {
            preview.close()
        }
    }

    /**
     * Что сервер скажет о человеке: имя и картинку. Тот, о ком он молчит, всё равно человек: возвращается один ID, и ему можно
     * писать.
     */
    suspend fun profile(userId: String): PersonProfile {
        val profile = runCatching { withTimeLimit(DIRECTORY_LIMIT) { client.getProfile(userId) } }.getOrNull()
            ?: return PersonProfile(userId, null, null)
        return PersonProfile(userId, profile.displayName, profile.avatarUrl)
    }

    /** Входит в комнату из каталога. */
    suspend fun joinRoom(room: PublicRoom): String = join(room.alias ?: room.id, emptyList())

    /** Каталог публичных комнат сервера, готовый к поиску. [PublicRoomDirectory.close], когда экран ушёл. */
    fun publicRooms(): PublicRoomDirectory = PublicRoomDirectory(client.roomDirectorySearch(), scope)

    /**
     * Создаёт комнату и ждёт, пока она станет комнатой, которую это устройство может открыть. `awaitRoomRemoteEcho` — вся
     * причина, почему это не гонка: `createRoom` возвращается, как только комната есть у *сервера*, за несколько шагов до
     * хранилища SDK, а следующее действие вызывающего — показать её. Без ожидания экран открывался бы на комнате, которой
     * клиент не слышал, и показывал заглушку «чат не выбран», пока идёт синхронизация, — а это выглядит как комната, что не
     * создалась. Ожидание ограничено, как всё, чего ждёт человек.
     */
    private suspend fun create(parameters: CreateRoomParameters): String {
        try {
            val roomId = withTimeLimit(CREATION_LIMIT) { client.createRoom(parameters) }
            awaitEcho(roomId)
            return roomId
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "room not created: ${Log.describe(error)}")
            throw ConversationException(ConversationFailure.CREATE_REFUSED)
        }
    }

    /** Входит и ждёт то же, по той же причине: следом комнату покажут. */
    private suspend fun join(idOrAlias: String, via: List<String>): String {
        try {
            val room = withTimeLimit(JOIN_LIMIT) { client.joinRoomByIdOrAlias(idOrAlias, via) }
            val roomId = room.id()
            room.close()
            awaitEcho(roomId)
            return roomId
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "room not joined: ${Log.describe(error)}")
            throw ConversationException(ConversationFailure.JOIN_REFUSED)
        }
    }

    /** Эхо — не условие: комната уже есть, и экран переживёт, если хранилище узнает о ней мигом позже. */
    private suspend fun awaitEcho(roomId: String) {
        try {
            withTimeLimit(ECHO_LIMIT) { client.awaitRoomRemoteEcho(roomId).close() }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
    }

    companion object {
        /** Создание — несколько круговых поездок на стороне сервера, ему дано больше, чем сообщению. */
        private val CREATION_LIMIT = 30.seconds
        private val ECHO_LIMIT = 10.seconds

        /** Вход по федерации — самое медленное, что делает сервер: состояние комнаты он может добирать с другого края света. */
        private val JOIN_LIMIT = 60.seconds
        private val DIRECTORY_LIMIT = 15.seconds

        /**
         * Похоже ли [text] на то, по чему находят комнату. Только вид; есть ли такая комната — скажет сервер. У псевдонима
         * сервер есть всегда; у ID комнаты он был, а с версии 12 нет — это `!` и хэш, — поэтому двоеточие спрашивается с
         * одного и не с другого.
         */
        fun isRoomAddress(text: String): Boolean {
            val candidate = text.trim()
            val sigil = candidate.firstOrNull() ?: return false
            if ((sigil != '#' && sigil != '!') || ' ' in candidate) return false
            val parts = candidate.drop(1).split(':', limit = 2)
            if (parts.size == 1) return sigil == '!' && parts[0].isNotEmpty()
            return parts[0].isNotEmpty() && parts[1].isNotEmpty()
        }
    }
}

/** Состояние каталога на экране. */
data class DirectoryState(
    val rooms: List<PublicRoom> = emptyList(),
    /** Страница в пути. */
    val isLoading: Boolean = false,
    /** Дальше по этому фильтру брать нечего. */
    val isAtEnd: Boolean = false,
    /** Последний запрос не вышел; следующий сбрасывает. */
    val failed: Boolean = false,
)

/**
 * Публичный каталог комнат сервера по страницам. SDK отдаёт его диффами над упорядоченным списком, как список чатов и
 * ленту, поэтому они применяются так же: одна очередь по порядку, позиционные диффы, значения наружу.
 */
class PublicRoomDirectory internal constructor(
    private val search: RoomDirectorySearch,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(DirectoryState())
    val state: StateFlow<DirectoryState> = mutableState

    private var handle: TaskHandle? = null
    private var updates: Job? = null
    private var generation = 0

    /** Начинает заново с [filter]. Пустой — весь каталог, людные первыми. */
    suspend fun search(filter: String) {
        listenIfNeeded()
        generation += 1
        val started = generation
        mutableState.update { it.copy(isLoading = true, failed = false, isAtEnd = false) }
        try {
            withTimeLimit(REQUEST_LIMIT) { search.search(filter.trim().ifEmpty { null }, 30u, null) }
            if (started != generation) return
            val atEnd = runCatching { search.isAtLastPage() }.getOrDefault(true)
            mutableState.update { it.copy(isAtEnd = atEnd, isLoading = false) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (started != generation) return
            Log.error(Log.Category.ROOMS, "room directory did not answer: ${Log.describe(error)}")
            mutableState.update { it.copy(failed = true, isLoading = false) }
        }
    }

    /** Следующая страница, если она есть и никакая не идёт. */
    suspend fun loadMore() {
        val now = mutableState.value
        if (now.isLoading || now.isAtEnd || now.failed) return
        val started = generation
        mutableState.update { it.copy(isLoading = true) }
        try {
            withTimeLimit(REQUEST_LIMIT) { search.nextPage() }
            if (started != generation) return
            val atEnd = runCatching { search.isAtLastPage() }.getOrDefault(true)
            mutableState.update { it.copy(isAtEnd = atEnd, isLoading = false) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (started != generation) return
            Log.error(Log.Category.ROOMS, "room directory did not page: ${Log.describe(error)}")
            mutableState.update { it.copy(failed = true, isLoading = false) }
        }
    }

    /** Экран ушёл: подписка отпускается, хендлы нативные, и GC о них не знает. */
    fun close() {
        handle?.cancel()
        handle = null
        updates?.cancel()
        updates = null
        search.close()
    }

    private suspend fun listenIfNeeded() {
        if (handle != null) return
        val batches = Channel<List<RoomDirectorySearchEntryUpdate>>(Channel.UNLIMITED)
        updates = scope.launch {
            for (batch in batches) mutableState.update { it.copy(rooms = apply(it.rooms, batch)) }
        }
        handle = search.results(
            object : RoomDirectorySearchEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomDirectorySearchEntryUpdate>) {
                    batches.trySend(roomEntriesUpdate)
                }
            },
        )
    }

    companion object {
        private val REQUEST_LIMIT = 20.seconds

        /** Диффы SDK поверх списка — по порядку, как они пришли. */
        internal fun apply(rooms: List<PublicRoom>, batch: List<RoomDirectorySearchEntryUpdate>): List<PublicRoom> {
            val list = rooms.toMutableList()
            for (update in batch) {
                when (update) {
                    is RoomDirectorySearchEntryUpdate.Append -> list.addAll(update.values.map(::PublicRoom))
                    is RoomDirectorySearchEntryUpdate.Clear -> list.clear()
                    is RoomDirectorySearchEntryUpdate.PushFront -> list.add(0, PublicRoom(update.value))
                    is RoomDirectorySearchEntryUpdate.PushBack -> list.add(PublicRoom(update.value))
                    is RoomDirectorySearchEntryUpdate.PopFront -> if (list.isNotEmpty()) list.removeAt(0)
                    is RoomDirectorySearchEntryUpdate.PopBack -> if (list.isNotEmpty()) list.removeAt(list.lastIndex)
                    is RoomDirectorySearchEntryUpdate.Insert -> list.add(update.index.toInt().coerceAtMost(list.size), PublicRoom(update.value))
                    is RoomDirectorySearchEntryUpdate.Set -> update.index.toInt().let { if (it < list.size) list[it] = PublicRoom(update.value) }
                    is RoomDirectorySearchEntryUpdate.Remove -> update.index.toInt().let { if (it < list.size) list.removeAt(it) }
                    is RoomDirectorySearchEntryUpdate.Truncate -> update.length.toInt().let { while (list.size > it) list.removeAt(list.lastIndex) }
                    is RoomDirectorySearchEntryUpdate.Reset -> {
                        list.clear()
                        list.addAll(update.values.map(::PublicRoom))
                    }
                }
            }
            return list
        }
    }
}
