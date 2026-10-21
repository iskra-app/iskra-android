package wtf.iskra.core.stickers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wtf.iskra.core.R
import wtf.iskra.core.mock.JSON
import wtf.iskra.core.mock.JsonValues
import wtf.iskra.core.mock.int
import wtf.iskra.core.mock.json
import wtf.iskra.core.mock.list
import wtf.iskra.core.mock.obj
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Что из стикеров не получилось — словами (`StickerFailure`). */
enum class StickerFailure(val explanation: Int) {
    ADD(R.string.stickers_add_failed),
    REMOVE(R.string.stickers_remove_failed),
    SUBSCRIBE(R.string.stickers_subscribe_failed),
    UNSUBSCRIBE(R.string.stickers_unsubscribe_failed),
    CLEAR_RECENT(R.string.stickers_clear_recent_failed),
}

/** Что показывает лист стикеров. */
data class StickerState(
    /** «Мои», пак этой комнаты, подписки, «Искорки» — по порядку, пустые не показываются. */
    val packs: List<StickerPack> = emptyList(),
    /** Недавние — в account data: общие для всех устройств аккаунта. */
    val recent: List<Sticker> = emptyList(),
    /** Картинки, которые уже в «Моих», — чтобы предложить «Убрать», а не «Добавить». */
    val mine: Set<String> = emptySet(),
    /** Паки комнат, на которые подписан аккаунт. */
    val subscribed: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val failure: StickerFailure? = null,
)

/**
 * Стикеры аккаунта — порт `StickerLibrary.swift`. Один на сеанс; лист читает его по открытии.
 *
 * Всё лежит на сервере и общее для устройств: свой пак и подписки — account data, недавние — тоже,
 * «Искорки» загружаются раз на аккаунт (`moscow.swag.iskra.builtin_stickers` помнит, где они). Источники —
 * функции, а не клиент SDK: так библиотеку проверяют тестами, а `UserSession` подставляет настоящие.
 */
class StickerLibrary(
    private val readAccountData: suspend (type: String) -> String?,
    private val writeAccountData: suspend (type: String, content: String) -> Unit,
    /** Состояние комнаты `im.ponies.room_emotes` с ключом — JSON или null, если пака нет. */
    private val readRoomPack: suspend (roomId: String, stateKey: String) -> String?,
    /** Загрузить картинку и получить её `mxc://`. Null — «Искорок» не будет (демо без загрузки). */
    private val upload: (suspend (mimeType: String, bytes: ByteArray) -> String)? = null,
    private val settings: () -> Sources = { Sources() },
) {
    /** Какие паки лист показывает, кроме своего, — «Настройки → Стикеры» (`StickerSettings`). */
    data class Sources(val builtIn: Boolean = true, val roomPack: Boolean = true, val mine: Boolean = true)

    private val mutableState = MutableStateFlow(StickerState())
    val state: StateFlow<StickerState> = mutableState.asStateFlow()

    private val builtInLock = Mutex()
    private var builtIn: StickerPack? = null

    /** Записанное, но, может быть, ещё не дошедшее до чтения: сервер отдаёт старое секунды после записи. */
    private val written = mutableMapOf<String, Pair<JSON, Long>>()

    /** Всё, что лист покажет в комнате [roomId]: свой пак, её пак, подписки, недавние, «Искорки». */
    suspend fun load(roomId: String?) {
        mutableState.update { it.copy(isLoading = true) }
        val sources = settings()
        val own = runCatching { current(ImagePack.USER_TYPE) }.getOrDefault(json())
        val mine = ImagePack.pack(own, StickerPack.Origin.Mine)
        val subscriptions = ImagePack.subscriptions(runCatching { current(ImagePack.SUBSCRIPTIONS_TYPE) }.getOrDefault(json()))
        val places = buildList {
            if (roomId != null && sources.roomPack) add(StickerPack.Origin.Room(roomId, ""))
            subscriptions.filterNot { it in this }.forEach(::add)
        }
        val roomPacks = coroutineScope {
            places.map { place -> async { roomPack(place) } }.awaitAll().filterNotNull()
        }
        val recent = recent(runCatching { current(RECENT_TYPE) }.getOrDefault(json()))
        val packs = ((if (sources.mine) listOf(mine) else emptyList()) + roomPacks).filter { it.stickers.isNotEmpty() }.toMutableList()
        mutableState.update {
            it.copy(
                packs = packs.toList(),
                recent = recent,
                mine = mine.stickers.mapTo(HashSet()) { s -> s.url },
                subscribed = subscriptions.mapTo(HashSet()) { s -> s.packId },
            )
        }
        if (sources.builtIn) builtInPack()?.takeIf { it.stickers.isNotEmpty() }?.let { pack -> packs += pack }
        mutableState.update { it.copy(packs = packs.toList(), isLoading = false, hasLoaded = true) }
    }

    /** Стикер отправили: наверх недавних (без повтора), не больше [RECENT_LIMIT]. */
    suspend fun used(sticker: Sticker) {
        val recent = pushing(sticker, mutableState.value.recent)
        mutableState.update { it.copy(recent = recent) }
        val images = recent.map { s -> json("url" to s.url, "body" to s.body).apply { if (s.info.isNotEmpty()) put("info", s.info) } }
        runCatching { write(RECENT_TYPE, json("images" to images)) }
    }

    /** Понравившийся стикер — в «Мои». */
    suspend fun add(sticker: Sticker) = changeOwn(StickerFailure.ADD, sticker.url, adding = true) { ImagePack.adding(sticker, it) }

    suspend fun remove(sticker: Sticker) = changeOwn(StickerFailure.REMOVE, sticker.url, adding = false) { ImagePack.removing(sticker.url, it) }

    /** Пак комнаты — «Добавить к себе»: его покажет лист в любой комнате. */
    suspend fun subscribe(pack: StickerPack) {
        val room = pack.origin as? StickerPack.Origin.Room ?: return
        mutableState.update { it.copy(subscribed = it.subscribed + pack.id) }
        try {
            writeRemembering(ImagePack.SUBSCRIPTIONS_TYPE, ImagePack.subscribing(room, current(ImagePack.SUBSCRIPTIONS_TYPE)))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "подписка на пак не записалась: ${Log.describe(error)}")
            mutableState.update { it.copy(subscribed = it.subscribed - pack.id, failure = StickerFailure.SUBSCRIBE) }
        }
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    private suspend fun changeOwn(failure: StickerFailure, url: String, adding: Boolean, change: (JSON) -> JSON) {
        mutableState.update { it.copy(mine = if (adding) it.mine + url else it.mine - url) }
        try {
            val content = change(current(ImagePack.USER_TYPE))
            writeRemembering(ImagePack.USER_TYPE, content)
            val pack = ImagePack.pack(content, StickerPack.Origin.Mine)
            mutableState.update { state ->
                val others = state.packs.filterNot { it.isMine }
                state.copy(packs = if (pack.stickers.isEmpty()) others else listOf(pack) + others, mine = pack.stickers.mapTo(HashSet()) { it.url })
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.MEDIA, "свой пак не изменился: ${Log.describe(error)}")
            mutableState.update { it.copy(mine = if (adding) it.mine - url else it.mine + url, failure = failure) }
        }
    }

    private suspend fun roomPack(place: StickerPack.Origin.Room): StickerPack? = try {
        withTimeLimit(TIMEOUT) { readRoomPack(place.roomId, place.stateKey) }?.let { ImagePack.pack(JsonValues.decodeObject(it), place) }
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    /**
     * «Искорки»: загружены ли на этот аккаунт — и какой версией. Недостающие загружаются и
     * записываются; не загрузилось — те, что есть, всё равно показываются.
     */
    private suspend fun builtInPack(): StickerPack? = builtInLock.withLock {
        builtIn?.let { return it }
        val upload = upload ?: return null
        val stored = runCatching { current(BUILT_IN_TYPE) }.getOrDefault(json())
        val images = if (stored["version"].int() == StickerArt.VERSION) LinkedHashMap(stored["images"].obj().orEmpty()) else LinkedHashMap()
        val missing = StickerArt.entries.filter { images[it.shortcode].obj()?.get("url") == null }
        if (missing.isNotEmpty()) {
            val uploaded = coroutineScope {
                missing.map { art ->
                    async {
                        val png = art.png()
                        if (png.isEmpty()) return@async null
                        runCatching { art to withTimeLimit(TIMEOUT) { upload("image/png", png) } to png.size }.getOrNull()
                    }
                }.awaitAll().filterNotNull()
            }
            for ((pair, size) in uploaded) {
                val (art, url) = pair
                val info = json("w" to StickerArt.SIDE, "h" to StickerArt.SIDE, "mimetype" to "image/png", "size" to size)
                images[art.shortcode] = json("url" to url, "body" to art.emoji, "usage" to listOf("sticker"), "info" to info)
            }
            if (uploaded.isNotEmpty()) {
                runCatching {
                    write(BUILT_IN_TYPE, json("version" to StickerArt.VERSION, "pack" to json("usage" to listOf("sticker")), "images" to images))
                }
            }
        }
        val stickers = StickerArt.entries.mapNotNull { art -> images[art.shortcode].obj()?.let { ImagePack.sticker(it, art.shortcode) } }
        StickerPack(StickerPack.Origin.BuiltIn, null, null, stickers).also { if (it.stickers.isNotEmpty()) builtIn = it }
    }

    /** Account data, а если мы её только что записали — записанное, пока сервер не догонит. */
    private suspend fun current(type: String): JSON {
        val stored = read(type)
        val (content, at) = written[type] ?: return stored
        if (System.currentTimeMillis() - at > WRITE_SETTLES_MS || stored == content) {
            written.remove(type)
            return stored
        }
        return content
    }

    private suspend fun read(type: String): JSON {
        val text = withTimeLimit(TIMEOUT) { readAccountData(type) } ?: return json()
        return JsonValues.decodeObject(text)
    }

    private suspend fun write(type: String, content: JSON) {
        withTimeLimit(TIMEOUT) { writeAccountData(type, JsonValues.encode(content)) }
    }

    private suspend fun writeRemembering(type: String, content: JSON) {
        write(type, content)
        written[type] = content to System.currentTimeMillis()
    }

    companion object {
        const val RECENT_LIMIT = 16
        const val RECENT_TYPE = "moscow.swag.iskra.recent_stickers"
        const val BUILT_IN_TYPE = "moscow.swag.iskra.builtin_stickers"
        private val TIMEOUT = 10.seconds
        private const val WRITE_SETTLES_MS = 60_000L

        internal fun pushing(sticker: Sticker, recent: List<Sticker>): List<Sticker> =
            (listOf(sticker) + recent.filter { it.url != sticker.url }).take(RECENT_LIMIT)

        internal fun recent(content: JSON): List<Sticker> =
            content["images"].list().orEmpty().mapNotNull { it.obj()?.let { image -> ImagePack.sticker(image, "") } }
    }
}
