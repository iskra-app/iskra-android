package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import wtf.iskra.core.R
import wtf.iskra.core.mock.JSON
import wtf.iskra.core.mock.JsonValues
import wtf.iskra.core.mock.json
import wtf.iskra.core.mock.list
import wtf.iskra.core.mock.obj
import wtf.iskra.core.mock.str
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Папка чатов, как у Telegram: имя и правило, какие чаты в неё входят. Порт `ChatFolder.swift`.
 *
 * Правило — несколько видов чатов (личные, группы, пространства, приглашения), суженных условиями (только
 * непрочитанные, без заглушённых, с архивом или без) и поправленных по имени: чаты, добавленные руками, внутри,
 * что бы ни говорили виды; убранные руками — снаружи, что бы ни говорило всё остальное. Папка — *фильтр*, не копия:
 * порядок внутри — порядок самого списка, а чат лежит во всех папках, что его описывают.
 *
 * В Matrix такого нет — ближайшее теги, но тег один на чат и аккаунт, а не правило, — поэтому папки лежат в
 * account data под ключом самого приложения и едут на все устройства аккаунта.
 */
data class ChatFolder(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kinds: Set<Kind> = emptySet(),
    /** Только чаты с непрочитанным — «исключить прочитанные» Telegram. */
    val onlyUnread: Boolean = false,
    /** Ни одного чата, заглушённого по имени. */
    val excludesMuted: Boolean = false,
    /** И архив. По умолчанию нет, как в Telegram: смахнутое остаётся смахнутым. */
    val includesArchived: Boolean = false,
    /** Всегда внутри, что бы ни говорили виды (условия всё равно действуют). По ID комнаты. */
    val included: List<String> = emptyList(),
    /** Всегда снаружи. Сильнее всего остального. */
    val excluded: List<String> = emptyList(),
) {
    enum class Kind(val key: String) {
        DIRECT("direct"),
        GROUPS("groups"),
        SPACES("spaces"),
        INVITATIONS("invitations"),
        ;

        companion object {
            fun of(key: String): Kind? = entries.firstOrNull { it.key == key }

            fun of(room: RoomSummary): Kind = when {
                room.isInvitation -> INVITATIONS
                room.isSpace -> SPACES
                room.isDirectMessage -> DIRECT
                else -> GROUPS
            }
        }
    }

    /** Лежит ли чат в папке. */
    fun contains(room: RoomSummary): Boolean {
        if (room.id in excluded) return false
        if (room.isArchived && !includesArchived) return false
        if (excludesMuted && room.isMuted) return false
        if (onlyUnread && !room.hasUnread && !room.isInvitation) return false
        return room.id in included || Kind.of(room) in kinds
    }

    /** Чаты папки из списка — в порядке списка. */
    fun rooms(from: List<RoomSummary>, archived: List<RoomSummary> = emptyList()): List<RoomSummary> =
        (if (includesArchived) from + archived else from).filter(::contains)

    /** Может ли папка когда-нибудь что-то вместить: имя без правила — папка, которая всегда пуста, и её не сохранить. */
    val isMeaningful: Boolean get() = name.isNotBlank() && (kinds.isNotEmpty() || included.isNotEmpty())

    companion object {
        /** С чего редактор предлагает начать — предложенные папки Telegram. */
        enum class Suggestion { PERSONAL, GROUPS, UNREAD }

        fun suggestion(which: Suggestion, name: String): ChatFolder = when (which) {
            Suggestion.PERSONAL -> ChatFolder(name = name, kinds = setOf(Kind.DIRECT))
            Suggestion.GROUPS -> ChatFolder(name = name, kinds = setOf(Kind.GROUPS))
            Suggestion.UNREAD -> ChatFolder(name = name, kinds = setOf(Kind.DIRECT, Kind.GROUPS), onlyUnread = true)
        }
    }
}

/** Что из папок не получилось — словами. */
enum class FolderFailure(val explanation: Int) {
    SAVE(R.string.folders_save_failed),
}

data class FoldersState(
    val folders: List<ChatFolder> = emptyList(),
    val hasLoaded: Boolean = false,
    val failure: FolderFailure? = null,
)

/**
 * Папки аккаунта: читаются из его account data, пишутся обратно при каждой правке. Порт `ChatFolders.swift`.
 *
 * Весь список — один документ, пишется целиком: папок горсть, меняются они несколько раз в год, и слияние правок
 * двух устройств не стоит того, чего стоило бы сделать его верно. Побеждает последняя запись, как у Telegram.
 * Источники — функции, а не клиент SDK: так библиотеку проверяют тестами, а `UserSession` подставляет настоящие.
 */
class ChatFolders(
    private val readAccountData: suspend (type: String) -> String?,
    private val writeAccountData: suspend (type: String, content: String) -> Unit,
) {
    private val mutableState = MutableStateFlow(FoldersState())
    val state: StateFlow<FoldersState> = mutableState.asStateFlow()

    /** Читает папки. Чтение, что не удалось, оставляет то, что на экране; нашедшее пустоту — аккаунт без папок. */
    suspend fun load() {
        val text = try {
            withTimeLimit(TIMEOUT) { readAccountData(TYPE) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutableState.update { it.copy(hasLoaded = true) }
            return
        }
        mutableState.update { it.copy(folders = decode(text.orEmpty()), hasLoaded = true) }
    }

    suspend fun save(folder: ChatFolder) {
        val current = mutableState.value.folders
        val index = current.indexOfFirst { it.id == folder.id }
        val next = when {
            index >= 0 -> current.toMutableList().also { it[index] = folder }
            current.size < LIMIT -> current + folder
            else -> return
        }
        write(next)
    }

    suspend fun delete(id: String) = write(mutableState.value.folders.filterNot { it.id == id })

    /** Переставить папку на [offset] мест (−1 — левее). Вне списка — ничего. */
    suspend fun move(id: String, offset: Int) {
        val current = mutableState.value.folders.toMutableList()
        val from = current.indexOfFirst { it.id == id }
        val to = from + offset
        if (from < 0 || to !in current.indices) return
        current.add(to, current.removeAt(from))
        write(current)
    }

    /** Положить чат в папку руками или убрать — то, что предлагает его собственное меню. */
    suspend fun set(roomId: String, folderId: String, isIn: Boolean) {
        val folder = mutableState.value.folders.firstOrNull { it.id == folderId } ?: return
        val included = folder.included.filterNot { it == roomId }
        val excluded = folder.excluded.filterNot { it == roomId }
        save(if (isIn) folder.copy(included = included + roomId, excluded = excluded) else folder.copy(included = included, excluded = excluded + roomId))
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    private suspend fun write(next: List<ChatFolder>) {
        val previous = mutableState.value.folders
        mutableState.update { it.copy(folders = next) }
        try {
            withTimeLimit(TIMEOUT) { writeAccountData(TYPE, encode(next)) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "папки не сохранились: ${Log.describe(error)}")
            mutableState.update { it.copy(folders = previous, failure = FolderFailure.SAVE) }
        }
    }

    companion object {
        /**
         * Всё ещё `moscow.swag.iskra`, прежняя приставка приложения, — нарочно: имя живёт на сервере, переживает
         * любую установку и общее с iOS, а переименование потеряло бы сохранённое под ним.
         */
        const val TYPE = "moscow.swag.iskra.chat_folders"

        /** Лимит Telegram, и больше вкладок, чем кто-либо прочтёт подряд. */
        const val LIMIT = 10
        private val TIMEOUT = 10.seconds

        internal fun encode(folders: List<ChatFolder>): String = JsonValues.encode(json("folders" to folders.map(::toJson)))

        /**
         * Снисходительно: папка, которую эта версия не прочтёт, пропускается, а не весь список — другое устройство
         * на более новой версии могло записать поле, которого эта не знает.
         */
        internal fun decode(text: String): List<ChatFolder> =
            JsonValues.decodeObject(text)["folders"].list().orEmpty().mapNotNull { fromJson(it.obj()) }

        private fun toJson(folder: ChatFolder): JSON = json(
            "id" to folder.id,
            "name" to folder.name,
            "kinds" to folder.kinds.map { it.key }.sorted(),
            "onlyUnread" to folder.onlyUnread,
            "excludesMuted" to folder.excludesMuted,
            "includesArchived" to folder.includesArchived,
            "included" to folder.included,
            "excluded" to folder.excluded,
        )

        private fun fromJson(item: JSON?): ChatFolder? {
            item ?: return null
            val id = item["id"].str() ?: return null
            val name = item["name"].str() ?: return null
            // Неизвестный вид («bots» с более новой версии) — не папка, а то, чего эта версия прочесть не может.
            val kinds = item["kinds"].list().orEmpty().map { key -> ChatFolder.Kind.of(key.str().orEmpty()) ?: return null }.toSet()
            return ChatFolder(
                id = id,
                name = name,
                kinds = kinds,
                onlyUnread = item["onlyUnread"] as? Boolean ?: false,
                excludesMuted = item["excludesMuted"] as? Boolean ?: false,
                includesArchived = item["includesArchived"] as? Boolean ?: false,
                included = item["included"].list().orEmpty().mapNotNull { it.str() },
                excluded = item["excluded"].list().orEmpty().mapNotNull { it.str() },
            )
        }
    }
}
