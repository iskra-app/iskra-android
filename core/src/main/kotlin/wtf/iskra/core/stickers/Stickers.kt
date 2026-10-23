package wtf.iskra.core.stickers

import wtf.iskra.core.mock.JSON
import wtf.iskra.core.mock.int
import wtf.iskra.core.mock.json
import wtf.iskra.core.mock.list
import wtf.iskra.core.mock.obj
import wtf.iskra.core.mock.str

/**
 * Стикеры, с которыми приходит приложение, — «Искорки»: розовая искра с бордовыми глазами, десять
 * настроений (`StickerArt.swift`). PNG 512×512 на прозрачном, нарисованы руками; привозит
 * `sync-from-ios`. Те же картинки стоят в паках демо.
 *
 * У стикера на сервере — `mxc://`, а у этих его нет, пока не понадобятся: [StickerLibrary] загружает
 * каждый раз на аккаунт и запоминает где — в account data. Поменялась картинка — подними [VERSION], и
 * каждый аккаунт загрузит набор заново при следующем открытии листа.
 */
enum class StickerArt(val emoji: String) {
    HELLO("👋"), JOY("🎉"), LOVE("❤️"), CURIOUS("🤔"), SURPRISE("😮"), SLEEPY("😴"),
    SAD("😢"), GRUMPY("😒"), LOADING("⏳"), UNLOCKED("🔓");

    val shortcode: String get() = name.lowercase()

    /** PNG стикера: фигура на *ничём*. Пусто, только если ресурса нет. */
    fun png(): ByteArray =
        StickerArt::class.java.getResourceAsStream("/stickers/sticker-$shortcode.png")?.use { it.readBytes() } ?: ByteArray(0)

    companion object {
        /** Сторона стикера: 512, как у Telegram и Element. */
        const val SIDE = 512

        /** Какой это набор картинок; набор, загруженный более старым, загружается заново. Как на iOS. */
        const val VERSION = 2
    }
}

/** Один стикер пака (MSC2545): картинка на сервере и слова вместо неё — `body`, их читает TalkBack. */
data class Sticker(
    val url: String,
    val shortcode: String,
    val body: String,
    val width: Int? = null,
    val height: Int? = null,
    val mimeType: String? = null,
    val size: Int? = null,
) {
    internal val info: JSON
        get() = json().apply {
            width?.let { put("w", it) }
            height?.let { put("h", it) }
            mimeType?.let { put("mimetype", it) }
            size?.let { put("size", it) }
        }

    /** Содержимое события `m.sticker` — для открытой комнаты, где стикер уходит ссылкой на картинку пака. */
    internal fun eventContent(replyingTo: String? = null): JSON = json("body" to body, "url" to url, "info" to info).apply {
        replyingTo?.let { put("m.relates_to", json("m.in_reply_to" to json("event_id" to it))) }
    }
}

/** Пак: свой, комнаты (её состояние `im.ponies.room_emotes` с ключом) или «Искорки». */
data class StickerPack(val origin: Origin, val name: String?, val iconUrl: String?, val stickers: List<Sticker>) {
    sealed interface Origin {
        val packId: String

        data object Mine : Origin {
            override val packId = "mine"
        }

        data class Room(val roomId: String, val stateKey: String) : Origin {
            override val packId get() = "$roomId/$stateKey"
        }

        data object BuiltIn : Origin {
            override val packId = "builtin"
        }
    }

    val id: String get() = origin.packId
    val isMine: Boolean get() = origin == Origin.Mine
    val isBuiltIn: Boolean get() = origin == Origin.BuiltIn
    val isRoomPack: Boolean get() = origin is Origin.Room

    /** Чем пак назван во вкладке: его картинка или первый стикер. */
    val icon: Sticker? get() = iconUrl?.let { Sticker(it, "", name.orEmpty()) } ?: stickers.firstOrNull()
}

/**
 * MSC2545 — тот же формат, что у Element, Cinny и FluffyChat (`ImagePack` в `StickerPack.swift`): свой
 * пак — account data `im.ponies.user_emotes`, паки комнат — состояние `im.ponies.room_emotes`, подписки —
 * `im.ponies.emote_rooms`.
 */
object ImagePack {
    const val USER_TYPE = "im.ponies.user_emotes"
    const val ROOM_TYPE = "im.ponies.room_emotes"
    const val SUBSCRIPTIONS_TYPE = "im.ponies.emote_rooms"

    /** Стикеры пака — те картинки, что годятся в стикеры (`usage` без `sticker` — это эмодзи), по коду. */
    internal fun stickers(content: JSON): List<Sticker> {
        val packUsage = content["pack"].obj()?.get("usage").list()?.mapNotNull { it.str() }
        return content["images"].obj().orEmpty().mapNotNull { (shortcode, value) ->
            val image = value.obj() ?: return@mapNotNull null
            val usage = image["usage"].list()?.mapNotNull { it.str() } ?: packUsage
            if (usage != null && "sticker" !in usage) return@mapNotNull null
            sticker(image, shortcode)
        }.sortedBy { it.shortcode.lowercase() }
    }

    internal fun sticker(image: JSON, shortcode: String): Sticker? {
        val url = image["url"].str()?.takeIf { it.startsWith("mxc://") } ?: return null
        val info = image["info"].obj().orEmpty()
        val body = image["body"].str()?.ifEmpty { null } ?: shortcode
        return Sticker(url, shortcode, body, info["w"].int(), info["h"].int(), info["mimetype"].str(), info["size"].int())
    }

    internal fun pack(content: JSON, origin: StickerPack.Origin): StickerPack {
        val pack = content["pack"].obj()
        return StickerPack(
            origin = origin,
            name = pack?.get("display_name").str()?.ifEmpty { null },
            iconUrl = pack?.get("avatar_url").str()?.takeIf { it.startsWith("mxc://") },
            stickers = stickers(content),
        )
    }

    /** Свой пак с ещё одним стикером. Уже есть (по картинке) — без изменений. */
    internal fun adding(sticker: Sticker, content: JSON): JSON {
        val images = content["images"].obj().orEmpty()
        if (images.values.any { it.obj()?.get("url") == sticker.url }) return content
        val image = json("url" to sticker.url, "usage" to listOf("sticker"))
        if (sticker.body.isNotEmpty()) image["body"] = sticker.body
        if (sticker.info.isNotEmpty()) image["info"] = sticker.info
        val updated = LinkedHashMap(images).apply { put(shortcode(sticker.body, keys), image) }
        return LinkedHashMap(content).apply { put("images", updated) }
    }

    internal fun removing(url: String, content: JSON): JSON {
        val images = content["images"].obj() ?: return content
        return LinkedHashMap(content).apply { put("images", images.filterValues { it.obj()?.get("url") != url }.toMutableMap()) }
    }

    /** Код стикера из его слов: строчные, пробелы — `_`, только буквы, цифры, `-` и `_`; занят — `_2`, `_3`. */
    internal fun shortcode(body: String, taken: Set<String>): String {
        val kept = body.lowercase().replace(' ', '_').filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(SHORTCODE_LIMIT)
        val stem = kept.ifEmpty { "sticker" }
        if (stem !in taken) return stem
        var number = 2
        while ("${stem}_$number" in taken) number++
        return "${stem}_$number"
    }

    /** Подписки: комнаты и ключи их паков, по порядку. */
    internal fun subscriptions(content: JSON): List<StickerPack.Origin.Room> =
        content["rooms"].obj().orEmpty().flatMap { (roomId, keys) -> keys.obj().orEmpty().keys.map { StickerPack.Origin.Room(roomId, it) } }
            .sortedWith(compareBy({ it.roomId }, { it.stateKey }))

    internal fun subscribing(room: StickerPack.Origin.Room, content: JSON): JSON {
        val rooms = LinkedHashMap(content["rooms"].obj().orEmpty())
        val keys = LinkedHashMap(rooms[room.roomId].obj().orEmpty())
        if (room.stateKey in keys) return content
        keys[room.stateKey] = json()
        rooms[room.roomId] = keys
        return LinkedHashMap(content).apply { put("rooms", rooms) }
    }

    internal fun unsubscribing(room: StickerPack.Origin.Room, content: JSON): JSON {
        val rooms = LinkedHashMap(content["rooms"].obj().orEmpty())
        val keys = LinkedHashMap(rooms[room.roomId].obj().orEmpty())
        keys.remove(room.stateKey)
        if (keys.isEmpty()) rooms.remove(room.roomId) else rooms[room.roomId] = keys
        return LinkedHashMap(content).apply { put("rooms", rooms) }
    }

    private const val SHORTCODE_LIMIT = 32
}
