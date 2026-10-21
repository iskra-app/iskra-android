package wtf.iskra.core.stickers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wtf.iskra.core.mock.JsonValues
import wtf.iskra.core.mock.json
import wtf.iskra.core.mock.obj

/** Порт `StickerPackTests.swift` и части `StickerDefaultsTests.swift`: формат MSC2545 и библиотека. */
class StickerPackTest {
    private fun image(url: String, body: String? = null, usage: List<String>? = null) =
        json("url" to url).apply { body?.let { put("body", it) }; usage?.let { put("usage", it) } }

    @Test
    fun anImageIsAStickerByItsOwnUsageThenThePacksThenBySayingNothing() {
        val content = json(
            "pack" to json("usage" to listOf("emoticon")),
            "images" to json(
                "emoji" to image("mxc://s/a"),
                "own" to image("mxc://s/b", usage = listOf("sticker")),
            ),
        )
        assertEquals(listOf("own"), ImagePack.stickers(content).map { it.shortcode })
        assertEquals(listOf("plain"), ImagePack.stickers(json("images" to json("plain" to image("mxc://s/c")))).map { it.shortcode })
    }

    @Test
    fun theDescriptionFallsBackToTheShortcode() {
        val sticker = ImagePack.sticker(json("url" to "mxc://s/a", "info" to json("w" to 256, "h" to 128, "mimetype" to "image/png")), "wave")!!
        assertEquals("wave", sticker.body)
        assertEquals(256, sticker.width)
        assertEquals("image/png", sticker.mimeType)
    }

    @Test
    fun addingKeepsEveryEmojiAndUnknownFieldAlreadyInThePack() {
        val content = json("images" to json("smile" to image("mxc://s/e", usage = listOf("emoticon"))), "custom" to "kept")
        val added = ImagePack.adding(Sticker("mxc://s/new", "", "привет мир"), content)
        assertEquals("kept", added["custom"])
        val images = added["images"].obj()!!
        assertTrue("smile" in images)
        assertEquals("mxc://s/new", images["привет_мир"].obj()?.get("url"))
    }

    @Test
    fun addingAPictureAlreadyInThePackChangesNothing() {
        val content = json("images" to json("a" to image("mxc://s/a")))
        assertEquals(content, ImagePack.adding(Sticker("mxc://s/a", "", "again"), content))
    }

    @Test
    fun removingTakesEveryEntryForThePicture() {
        val content = json("images" to json("a" to image("mxc://s/a"), "b" to image("mxc://s/a"), "c" to image("mxc://s/c")))
        assertEquals(setOf("c"), ImagePack.removing("mxc://s/a", content)["images"].obj()!!.keys)
    }

    @Test
    fun aShortcodeIsMadeUnique() {
        assertEquals("hello_there", ImagePack.shortcode("Hello there", emptySet()))
        assertEquals("hello_2", ImagePack.shortcode("hello", setOf("hello")))
        assertEquals("sticker", ImagePack.shortcode("👋", emptySet()))
    }

    @Test
    fun subscriptionsAreOneEntryPerRoomAndStateKey() {
        val content = json("rooms" to json("!b:s" to json("" to json()), "!a:s" to json("x" to json(), "" to json())))
        assertEquals(
            listOf(StickerPack.Origin.Room("!a:s", ""), StickerPack.Origin.Room("!a:s", "x"), StickerPack.Origin.Room("!b:s", "")),
            ImagePack.subscriptions(content),
        )
        val back = ImagePack.unsubscribing(StickerPack.Origin.Room("!b:s", ""), ImagePack.subscribing(StickerPack.Origin.Room("!c:s", ""), content))
        assertEquals(setOf("!a:s", "!c:s"), back["rooms"].obj()!!.keys)
    }

    @Test
    fun theEventIsTheDescriptionUrlAndSizeAndAReplyNamesItsEvent() {
        val event = Sticker("mxc://s/a", "", "👋", 512, 512, "image/png").eventContent(replyingTo = "\$e")
        assertEquals("👋", event["body"])
        assertEquals(512, event["info"].obj()?.get("w"))
        assertEquals("\$e", event["m.relates_to"].obj()?.get("m.in_reply_to").obj()?.get("event_id"))
    }

    @Test
    fun theRecentRowPutsTheNewestFirstOnceAndKeepsSixteen() {
        val many = (1..20).map { Sticker("mxc://s/$it", "", "$it") }
        val recent = StickerLibrary.pushing(many[3], many.take(16))
        assertEquals(StickerLibrary.RECENT_LIMIT, recent.size)
        assertEquals("mxc://s/4", recent.first().url)
        assertEquals(1, recent.count { it.url == "mxc://s/4" })
    }

    /** Account data в памяти — то, что видит библиотека вместо сервера. `runBlocking`, не `runTest`: виртуальное время `runTest` мгновенно истекает `withTimeLimit`, пока работа идёт на IO. */
    private class Account(var uploadWorks: Boolean = true) {
        val data = mutableMapOf<String, String>()
        val roomPacks = mutableMapOf<String, String>()
        var uploads = 0
        fun library() = StickerLibrary(
            readAccountData = { data[it] },
            writeAccountData = { type, content -> data[type] = content },
            readRoomPack = { roomId, key -> roomPacks["$roomId/$key"] },
            upload = { _, _ -> if (!uploadWorks) error("нет сети"); uploads++; "mxc://s/up$uploads" },
        )
    }

    @Test
    fun theAccountsPackFirstThenTheRoomsThenSubscriptionsAndTheSparks() = runBlocking {
        val account = Account()
        account.data[ImagePack.USER_TYPE] = JsonValues.encode(json("images" to json("mine" to image("mxc://s/m"))))
        account.data[ImagePack.SUBSCRIPTIONS_TYPE] = JsonValues.encode(json("rooms" to json("!other:s" to json("" to json()))))
        account.roomPacks["!room:s/"] = JsonValues.encode(json("images" to json("room" to image("mxc://s/r"))))
        account.roomPacks["!other:s/"] = JsonValues.encode(json("images" to json("other" to image("mxc://s/o"))))
        account.roomPacks["!empty:s/"] = JsonValues.encode(json("images" to json()))
        val library = account.library()
        library.load("!room:s")
        assertEquals(listOf("mine", "!room:s/", "!other:s/", "builtin"), library.state.value.packs.map { it.id })
    }

    @Test
    fun twoAddsBeforeSyncHasBroughtTheFirstBackKeepBoth() = runBlocking {
        val account = Account()
        val library = account.library()
        library.add(Sticker("mxc://s/1", "", "one"))
        library.add(Sticker("mxc://s/2", "", "two"))
        val images = JsonValues.decodeObject(account.data.getValue(ImagePack.USER_TYPE))["images"].obj()!!
        assertEquals(2, images.size)
    }

    @Test
    fun aSentStickerGoesToTheFrontOfTheRecentRowInTheAccount() = runBlocking {
        val account = Account()
        val library = account.library()
        library.used(Sticker("mxc://s/1", "", "one"))
        library.used(Sticker("mxc://s/2", "", "two"))
        val fresh = account.library().apply { load(null) }
        assertEquals(listOf("mxc://s/2", "mxc://s/1"), fresh.state.value.recent.map { it.url })
    }

    @Test
    fun theSparksAreUploadedOncePerAccountThenReadBackWithoutUploadingAgain() = runBlocking {
        val account = Account()
        account.library().load(null)
        val first = account.uploads
        assertEquals(StickerArt.entries.size, first)
        val again = account.library().apply { load(null) }
        assertEquals(first, account.uploads)
        assertEquals(StickerArt.entries.size, again.state.value.packs.single { it.isBuiltIn }.stickers.size)
    }

    @Test
    fun aSetDrawnByAnOlderVersionIsUploadedAgain() = runBlocking {
        val account = Account()
        account.library().load(null)
        val stored = JsonValues.decodeObject(account.data.getValue(StickerLibrary.BUILT_IN_TYPE)).apply { put("version", StickerArt.VERSION - 1) }
        account.data[StickerLibrary.BUILT_IN_TYPE] = JsonValues.encode(stored)
        account.library().load(null)
        assertEquals(StickerArt.entries.size * 2, account.uploads)
    }

    @Test
    fun withNoNetworkTheFirstTimeThereIsNoPackAndNothingIsWritten() = runBlocking {
        val account = Account(uploadWorks = false)
        val library = account.library().apply { load(null) }
        assertTrue(library.state.value.packs.none { it.isBuiltIn })
        assertTrue(StickerLibrary.BUILT_IN_TYPE !in account.data)
    }

    @Test
    fun everyDrawingIsThere() {
        assertTrue(StickerArt.entries.all { it.png().isNotEmpty() })
    }
}
