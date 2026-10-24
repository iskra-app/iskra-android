package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.RoomType
import org.matrix.rustcomponents.sdk.SpaceRoom
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Комната или вложенное пространство внутри пространства — строка экрана (`SpaceRoomSummary`). */
data class SpaceRoomSummary(
    val id: String,
    val name: String,
    val topic: String? = null,
    val avatarUrl: String? = null,
    val memberCount: Int = 0,
    val isSpace: Boolean = false,
)

data class SpaceState(val rooms: List<SpaceRoomSummary> = emptyList(), val isLoading: Boolean = true)

/**
 * Что внутри пространства: комнаты и вложенные пространства, одним списком (`SpaceStore` на iOS). Страницы подтягиваются, пока их
 * не наберётся [MAX_PAGES]: пространство на сотни комнат — не повод держать экран в ожидании, а то, чего не хватило, видно в каталоге сервера.
 */
class SpaceStore internal constructor(private val client: Client, private val spaceId: String) {
    private val mutable = MutableStateFlow(SpaceState())
    val state: StateFlow<SpaceState> = mutable.asStateFlow()

    suspend fun load() {
        try {
            val service = client.spaceService()
            val list = withTimeLimit(LIMIT) { service.spaceRoomList(spaceId) }
            repeat(MAX_PAGES) {
                try {
                    withTimeLimit(LIMIT) { list.paginate() }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    return@repeat
                }
            }
            val rooms = withTimeLimit(LIMIT) { list.rooms() }
            mutable.value = SpaceState(rooms.filter { it.roomId != spaceId }.map(::summary), isLoading = false)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "пространство не открылось: ${Log.describe(error)}")
            mutable.value = SpaceState(isLoading = false)
        }
    }

    private fun summary(room: SpaceRoom) = SpaceRoomSummary(
        id = room.roomId,
        name = room.displayName,
        topic = room.topic,
        avatarUrl = room.avatarUrl,
        memberCount = room.numJoinedMembers.toInt(),
        isSpace = room.roomType == RoomType.Space,
    )

    private companion object {
        const val MAX_PAGES = 5
        val LIMIT = 30.seconds
    }
}
