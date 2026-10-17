package moscow.swag.iskra.core.mock

/** Комната демо-сервера. Всё изменяемое — под замком [MockHomeserver]. */
internal class MockRoom(val id: String) {
    /** Все события, старые первыми, состояние тоже. */
    val events = mutableListOf<JSON>()

    /** Текущее состояние по `type` + NUL + `state_key`. */
    val state = linkedMapOf<String, JSON>()

    /** Данные комнаты по типу: теги, `m.fully_read`. */
    val accountData = linkedMapOf<String, JSON>()

    /** Квитанции по пользователю: до какого события прочитано и когда. */
    val receipts = linkedMapOf<String, Receipt>()
    val typing = linkedSetOf<String>()

    /** Растёт от всего, что должна унести синхронизация: событие, квитанция, «печатает», данные. */
    var revision = 0

    data class Receipt(val eventId: String, val ts: Long)
}

internal data class MockProfile(var name: String, var avatar: String?)

/** Стикеры «Искорки» — те же PNG, что у iOS (`StickerArt`), привезённые `sync-from-ios`. */
internal enum class StickerArt(val emoji: String) {
    HELLO("👋"), JOY("🎉"), LOVE("❤️"), CURIOUS("🤔"), SURPRISE("😮"), SLEEPY("😴"),
    SAD("😢"), GRUMPY("😒"), LOADING("⏳"), UNLOCKED("🔓"), BYPASS("🏃"), CONNECTED("📶");

    val shortcode: String get() = name.lowercase()

    /** PNG стикера: фигура на *ничём*. Пусто, только если ресурса нет, — это держит тест. */
    fun png(): ByteArray =
        StickerArt::class.java.getResourceAsStream("/stickers/sticker-$shortcode.png")?.use { it.readBytes() } ?: ByteArray(0)

    companion object {
        /** Сторона стикера: 512, как у Telegram и Element. */
        const val SIDE = 512
    }
}

/**
 * С чем демо-сервер начинает: люди, разговоры, картинки. Порт `MockWorld.swift` — **один в один**
 * (docs/PROMPT.md, 14), чтобы обе платформы показывали один и тот же мир.
 *
 * Написан так, чтобы каждое, что рисует приложение, было на экране хотя бы раз: ответ, правка,
 * реакции, удалённое, фото, голосовое, файл, геопозиция, опрос, упоминание, форматирование,
 * закреплённое, пространство, приглашение, длинная комната для прокрутки, человек без картинки.
 * По-русски: приложение пишется русским первым.
 */
internal class MockWorld(private val now: Long = System.currentTimeMillis()) {
    val me = user("sasha")
    val rooms = linkedMapOf<String, MockRoom>()
    val profiles = linkedMapOf<String, MockProfile>()
    val accountData = linkedMapOf<String, JSON>()
    val media = linkedMapOf<String, Pair<ByteArray, String>>()
    val publicRoomIds = mutableListOf<String>()

    private val stickers = linkedMapOf<StickerArt, JSON>()
    private var counter = 0

    /** Минута, в которую пишутся события, по комнате — назад от «сейчас». Выше `init`: он её пишет. */
    private val clock = hashMapOf<String, Long>()

    private val anya = user("anya")
    private val misha = user("misha")
    private val mama = user("mama")
    private val papa = user("papa")
    private val lena = user("lena")
    private val dima = user("dima")
    private val olga = user("olga")
    private val kostya = user("kostya")

    init {
        person(me, "Саша", 0.58)
        person(anya, "Аня Смирнова", 0.95)
        person(misha, "Миша", null)
        person(mama, "Мама", 0.08)
        person(papa, "Папа", null)
        person(lena, "Лена Котова", 0.75)
        person(dima, "Дима", null)
        person(olga, "Ольга", 0.45)
        person(kostya, "Костя Орлов", 0.3)
        drawStickers()

        val anyaChat = directWithAnya()
        val mishaChat = directWithMisha()
        val lenaChat = directWithLena()
        family()
        development()
        workSpace()
        notes()
        bookClubInvitation()
        swiftCommunity()

        accountData["m.direct"] = json(anya to listOf(anyaChat), misha to listOf(mishaChat), lena to listOf(lenaChat))
        accountData["m.push_rules"] = pushRules(me)
        // Четыре из восьми — свои, остальные четыре — у комнаты разработки: стикер, которого у тебя
        // ещё нет, попадается в чате, и его можно добавить.
        accountData["im.ponies.user_emotes"] = json("images" to pack(StickerArt.HELLO, StickerArt.LOVE, StickerArt.JOY, StickerArt.SLEEPY))
        // Две папки — вкладкам над списком есть что показать.
        accountData[CHAT_FOLDERS_TYPE] = json(
            "folders" to listOf(
                json(
                    "id" to "demo-personal", "name" to "Личные", "kinds" to listOf("direct"), "onlyUnread" to false,
                    "excludesMuted" to false, "includesArchived" to false, "included" to listOf<String>(), "excluded" to listOf<String>(),
                ),
                json(
                    "id" to "demo-unread", "name" to "Непрочитанные", "kinds" to listOf("direct", "groups"), "onlyUnread" to true,
                    "excludesMuted" to true, "includesArchived" to false, "included" to listOf<String>(), "excluded" to listOf<String>(),
                ),
            ),
        )
    }

    // MARK: Разговоры

    private fun directWithAnya(): String {
        val room = makeRoom("anya", members = listOf(me, anya), admins = listOf(me, anya), minutesAgo = 60 * 24 * 3)
        at(room, 60 * 24 * 3 - 1)
        text(room, anya, "Привет! Ты завтра свободна вечером?")
        text(room, me, "Привет) Да, после семи — вполне")
        val plan = text(room, anya, "Отлично. Давай в «Жан-Жак» на Никитском? Там сейчас тихо по будням")
        react(room, plan, me, "👍")

        at(room, 60 * 24 * 2)
        image(room, anya, "набережная.png", hue = 0.55)
        text(room, anya, "Смотри, какой вчера был закат")
        val wow = text(room, me, "Ого, красота какая. Это где?")
        reply(room, anya, wow, "На Крымской набережной, у Музеона")
        react(room, wow, anya, "❤️")

        at(room, 60 * 5)
        val typo = text(room, me, "Я буду минут через дядцать")
        edit(room, me, typo, "Я буду минут через двадцать")
        val secret = text(room, anya, "Ок, я уже тут, взяла нам столик у окна")
        redact(room, secret, anya)
        location(room, anya, "Я здесь", 55.7575, 37.5979)
        voice(room, anya)
        val file = file(room, me, "список-на-выходные.txt", "— купить подарок Мише\n— забрать книги у Лены\n— позвонить маме\n")
        react(room, file, anya, "🙏")
        at(room, 12)
        text(room, anya, "😂")
        sticker(room, anya, StickerArt.LOVE)
        val long = text(
            room, anya,
            "Кстати, дочитала ту книгу, что ты советовала. Первая половина прямо тяжело шла, я даже " +
                "хотела бросить, но потом как разогналось — не могла оторваться до трёх ночи. " +
                "Особенно глава про маяк. Давай в субботу обсудим?",
        )
        react(room, long, me, "🔥")
        react(room, long, me, "❤️")
        at(room, 3)
        formatted(room, anya, "Не забудь **зонт** — обещали дождь", "Не забудь <b>зонт</b> — обещали <i>дождь</i>")
        text(room, anya, "И захвати, пожалуйста, зарядку 🙌")
        read(room, me, 2)
        return room
    }

    private fun directWithMisha(): String {
        val room = makeRoom("misha", members = listOf(me, misha), admins = listOf(me, misha), minutesAgo = 60 * 24 * 10)
        at(room, 60 * 24 * 10 - 5)
        text(room, me, "Миш, с днём рождения! 🎉")
        text(room, misha, "Спасибо!!")
        at(room, 40)
        text(room, misha, "Слушай")
        text(room, misha, "А ты не помнишь, как называлась та игра, в которую мы играли у Димы?")
        text(room, misha, "Там ещё надо было угадывать слова по картинкам")
        text(room, misha, "Кажется «Имаджинариум», но не уверен")
        at(room, 25)
        text(room, misha, "Ладно, нашёл, это «Диксит» 😅")
        sticker(room, misha, StickerArt.SURPRISE)
        read(room, me, 3)
        return room
    }

    private fun directWithLena(): String {
        val room = makeRoom("lena", members = listOf(me, lena), admins = listOf(me, lena), minutesAgo = 60 * 24 * 40)
        at(room, 60 * 24 * 40 - 10)
        text(room, lena, "Саш, я верну книги на следующей неделе, ладно?")
        text(room, me, "Конечно, не торопись")
        at(room, 60 * 24 * 21)
        text(room, lena, "Спасибо ещё раз за «Шантарам», это было что-то")
        rooms.getValue(room).accountData["m.tag"] = json("tags" to json("m.lowpriority" to json("order" to 0.5)))
        return room
    }

    private fun family() {
        val room = makeRoom(
            "family", members = listOf(me, mama, papa), admins = listOf(mama),
            name = "Семья 🏡", topic = "Дача, дни рождения и кто что купил", avatarHue = 0.1, minutesAgo = 60 * 24 * 60,
        )
        at(room, 60 * 24 * 60 - 1)
        text(room, mama, "Всем привет! Сделала нам общий чат")
        val pinned = text(room, mama, "Код от калитки на даче: 2580. Не теряйте!")
        text(room, papa, "Принято")
        at(room, 60 * 24 * 6)
        // Альбом — три фото с разницей в секунду, подпись на первом, как это приложение его шлёт.
        val harvest = image(room, papa, "грядка.png", hue = 0.24, width = 800, height = 1200, caption = "Первые кабачки в этом году")
        image(room, papa, "урожай.png", hue = 0.28)
        image(room, papa, "кабачки.png", hue = 0.32)
        react(room, pinned, papa, "👌")
        // Треды: один под альбомом, и свежий у самого низа.
        inThread(room, mama, harvest, "Какие красивые! Оставь мне пару штук")
        inThread(room, papa, harvest, "Конечно, в воскресенье привезу")
        at(room, 60 * 3)
        val trip = text(room, mama, "Что везти на дачу в субботу? Пишите сюда, в тред")
        inThread(room, papa, trip, "Удобрения и лейку, старая потекла")
        inThread(room, mama, trip, "И семена укропа")
        inThread(room, me, trip, "Я возьму мангал")
        at(room, 60 * 2)
        text(room, mama, "Саша, ты в воскресенье приедешь?")
        mention(room, mama, "Саша", "Саша, ответь маме 🙂")
        state(room, mama, "m.room.pinned_events", json("pinned" to listOf(pinned)))
        read(room, me, 5)
    }

    private fun development() {
        val room = makeRoom(
            "iskra-dev", members = listOf(me, lena, dima, anya, misha, kostya), admins = listOf(me),
            name = "Искра — разработка", topic = "Клиент Matrix для iPhone, iPad и Mac", avatarHue = 0.02, minutesAgo = 60 * 24 * 30,
        )

        // Довольно истории, чтобы долго листать назад и смотреть, как она приходит страницами.
        val chatter = listOf(
            dima to "Собрал ветку со звонками, у кого есть минутка проверить?",
            lena to "Могу вечером",
            kostya to "Я посмотрю утром, сегодня уже не успею",
            misha to "А macOS-сборка сейчас зелёная?",
            dima to "Да, после вчерашнего фикса всё собирается",
            me to "Отлично, спасибо!",
            lena to "Кто-нибудь знает, почему симулятор стал так долго загружаться?",
            kostya to "Попробуй удалить DerivedData, мне помогло",
            anya to "Нарисовала новые иконки, закинула в фигму",
            me to "Шикарно получилось 😍",
        )
        at(room, 60 * 24 * 30 - 5)
        for (day in 0 until 14) {
            for ((offset, line) in chatter.withIndex()) {
                at(room, 60 * 24 * (29 - day) - offset * 7)
                text(room, line.first, line.second)
            }
        }

        at(room, 60 * 26)
        state(room, kostya, "m.room.member", memberContent(kostya, "Костя"), key = kostya)
        val code = formatted(
            room, dima,
            "Вот так теперь выглядит запуск:\n```\n./device.sh --here --no-launch\n```",
            "<p>Вот так теперь выглядит запуск:</p><pre><code>./device.sh --here --no-launch</code></pre>",
        )
        react(room, code, lena, "🚀")
        react(room, code, me, "🚀")
        react(room, code, kostya, "👀")
        val release = text(room, misha, "Что делаем с пушами на macOS — откладываем до следующего релиза?")
        inThread(room, dima, release, "Я бы отложил, там ещё entitlements разбирать")
        inThread(room, lena, release, "Согласна, iOS важнее")
        inThread(room, misha, release, "Ок, тогда переношу задачу")
        val call = poll(room, lena, "Когда созвонимся на неделе?", listOf("Во вторник", "В среду", "В четверг"))
        vote(room, dima, call, listOf("a1"))
        vote(room, kostya, call, listOf("a0"))
        at(room, 60 * 3)
        formatted(
            room, kostya, "Список на релиз:\n1. Иконки\n2. Демо-режим\n3. Перевод",
            "<p>Список на релиз:</p><ol><li>Иконки</li><li>Демо-режим</li><li>Перевод</li></ol>",
        )
        formatted(
            room, lena, "> Сначала сделай, потом сделай красиво\nА чем кончится сериал — спойлер",
            "<blockquote><p>Сначала сделай, потом сделай <u>красиво</u></p></blockquote>" +
                "<p>А чем кончится сериал — <span data-mx-spoiler>все выживут</span></p>",
        )
        val question = mention(room, dima, "Саша", "Саша, глянешь мой PR? Там немного, честно")
        reply(room, lena, question, "Я уже посмотрела, там всё хорошо 👍")
        text(room, anya, "Ссылка на макеты: https://matrix.org")
        state(
            room, me, "im.ponies.room_emotes",
            json(
                "pack" to json("display_name" to "Искра", "usage" to listOf("sticker")),
                "images" to pack(StickerArt.CONNECTED, StickerArt.UNLOCKED, StickerArt.SURPRISE, StickerArt.BYPASS),
            ),
        )
        sticker(room, kostya, StickerArt.CONNECTED)

        rooms.getValue(room).accountData["m.tag"] = json("tags" to json("m.favourite" to json("order" to 0.1)))
        read(room, me, rooms.getValue(room).events.size - 5)
    }

    private fun workSpace() {
        val general = makeRoom(
            "work-general", members = listOf(me, kostya, dima, olga), admins = listOf(olga),
            name = "Общий", topic = "Всё про работу", minutesAgo = 60 * 24 * 90,
        )
        at(general, 60 * 8)
        text(general, olga, "Коллеги, напоминаю: в пятницу выходим на час раньше 🎉")
        text(general, dima, "Ура!")

        val offtop = makeRoom(
            "work-offtop", members = listOf(kostya, dima, olga), admins = listOf(olga),
            name = "Офтоп", topic = "Котики, мемы и обеды", joinRule = "public", minutesAgo = 60 * 24 * 90,
        )
        at(offtop, 60 * 30)
        text(offtop, kostya, "Кто на обед в 13:00?")
        text(offtop, dima, "Я!")

        val space = makeRoom(
            "work", members = listOf(me, kostya, dima, olga), admins = listOf(olga),
            name = "Работа", topic = "Пространство команды", avatarHue = 0.62, isSpace = true, minutesAgo = 60 * 24 * 90,
        )
        for (child in listOf(general, offtop)) {
            state(space, olga, "m.space.child", json("via" to listOf(SERVER)), key = child)
            state(child, olga, "m.space.parent", json("via" to listOf(SERVER), "canonical" to true), key = space)
        }
        publicRoomIds += offtop
    }

    private fun notes() {
        val room = makeRoom("notes", members = listOf(me), admins = listOf(me), name = "Заметки", topic = "Только для себя", minutesAgo = 60 * 24 * 14)
        at(room, 60 * 24 * 14 - 1)
        text(room, me, "Идеи подарков: плед, настолка, термос")
        text(room, me, "Пароль от вайфая на даче — на холодильнике")
        at(room, 60 * 24 * 4)
        image(room, me, "скриншот.png", hue = 0.8)
    }

    private fun bookClubInvitation() {
        val room = makeRoom(
            "books", members = listOf(olga, lena), admins = listOf(olga), name = "Книжный клуб 📚",
            topic = "Читаем по книге в месяц", avatarHue = 0.35, minutesAgo = 60 * 24 * 5,
        )
        at(room, 60 * 24 * 5 - 1)
        text(room, olga, "В этом месяце читаем «Лавра» Водолазкина")
        at(room, 50)
        val content = memberContent(me, "Саша")
        content["membership"] = "invite"
        state(room, olga, "m.room.member", content, key = me)
    }

    private fun swiftCommunity() {
        val room = makeRoom(
            "swift", members = listOf(kostya, misha, anya), admins = listOf(kostya), name = "Swift по-русски",
            topic = "Открытое сообщество", avatarHue = 0.05, joinRule = "public", minutesAgo = 60 * 24 * 200,
        )
        at(room, 60 * 24 * 2)
        text(room, kostya, "Кто уже попробовал новые макросы в Swift 7?")
        text(room, misha, "Я, очень удобно для тестов")
        publicRoomIds += room
    }

    // MARK: Кирпичи

    private fun at(room: String, minutesAgo: Int) {
        clock[room] = now - minutesAgo * MINUTE
    }

    private fun makeRoom(
        slug: String,
        members: List<String>,
        admins: List<String>,
        name: String? = null,
        topic: String? = null,
        avatarHue: Double? = null,
        isSpace: Boolean = false,
        joinRule: String = "invite",
        minutesAgo: Int,
    ): String {
        val id = "!$slug:$SERVER"
        rooms[id] = MockRoom(id)
        at(id, minutesAgo)
        val creator = admins.firstOrNull() ?: members[0]
        val create = json("room_version" to "11", "creator" to creator)
        if (isSpace) create["type"] = "m.space"
        state(id, creator, "m.room.create", create)
        for (member in members) state(id, member, "m.room.member", memberContent(member), key = member)
        state(id, creator, "m.room.power_levels", powerLevels(admins))
        state(id, creator, "m.room.join_rules", json("join_rule" to joinRule))
        state(id, creator, "m.room.history_visibility", json("history_visibility" to "shared"))
        if (name != null) state(id, creator, "m.room.name", json("name" to name))
        if (topic != null) state(id, creator, "m.room.topic", json("topic" to topic))
        if (avatarHue != null) state(id, creator, "m.room.avatar", json("url" to store(MockMedia.avatar(avatarHue), "image/png")))
        return id
    }

    private fun event(room: String, sender: String, type: String, content: JSON, key: String? = null, extra: JSON = json()): String {
        counter += 1
        val slug = room.drop(1).substringBefore(':')
        val id = "\$$slug-$counter"
        val ts = (clock[room] ?: now) + SECOND
        clock[room] = minOf(ts, now)
        val event = json(
            "event_id" to id, "type" to type, "sender" to sender, "room_id" to room,
            "origin_server_ts" to ts, "content" to content, "unsigned" to json(),
        )
        if (key != null) event["state_key"] = key
        event.putAll(extra)
        val target = rooms.getValue(room)
        target.events += event
        if (key != null) target.state["$type\u0000$key"] = event
        // Человек прочёл то, что написал.
        target.receipts[sender] = MockRoom.Receipt(id, ts)
        return id
    }

    private fun state(room: String, sender: String, type: String, content: JSON, key: String = ""): String =
        event(room, sender, type, content, key = key)

    private fun text(room: String, sender: String, body: String): String =
        event(room, sender, "m.room.message", json("msgtype" to "m.text", "body" to body))

    private fun formatted(room: String, sender: String, body: String, html: String): String =
        event(
            room, sender, "m.room.message",
            json("msgtype" to "m.text", "body" to body, "format" to "org.matrix.custom.html", "formatted_body" to html),
        )

    private fun mention(room: String, sender: String, name: String, body: String): String {
        val link = "<a href=\"https://matrix.to/#/$me\">$name</a>"
        val html = if (body.startsWith(name)) link + body.removePrefix(name) else body
        return event(
            room, sender, "m.room.message",
            json(
                "msgtype" to "m.text", "body" to body, "format" to "org.matrix.custom.html",
                "formatted_body" to html, "m.mentions" to json("user_ids" to listOf(me)),
            ),
        )
    }

    private fun reply(room: String, sender: String, target: String, body: String): String =
        event(
            room, sender, "m.room.message",
            json("msgtype" to "m.text", "body" to body, "m.relates_to" to json("m.in_reply_to" to json("event_id" to target))),
        )

    /** Ответ в треде под [root] — с запасным ответом для старых клиентов. */
    private fun inThread(room: String, sender: String, root: String, body: String): String {
        val id = event(
            room, sender, "m.room.message",
            json(
                "msgtype" to "m.text", "body" to body,
                "m.relates_to" to json(
                    "rel_type" to "m.thread", "event_id" to root, "is_falling_back" to true,
                    "m.in_reply_to" to json("event_id" to root),
                ),
            ),
        )
        // Что настоящий сервер приклеивает к корню (`unsigned.m.relations.m.thread`): сколько ответов,
        // последний и участвовал ли читающий. «3 ответа» лента читает отсюда.
        val events = rooms.getValue(room).events
        val rootIndex = events.indexOfFirst { it["event_id"] == root }
        val latest = events.lastOrNull { it["event_id"] == id }
        if (rootIndex >= 0 && latest != null) {
            val copy = events[rootIndex].copy()
            val unsigned = copy["unsigned"].obj()?.copy() ?: json()
            val relations = unsigned["m.relations"].obj()?.copy() ?: json()
            val previous = relations["m.thread"].obj()
            val count = (previous?.get("count").int() ?: 0) + 1
            val participated = (previous?.get("current_user_participated") as? Boolean ?: false) || sender == me
            relations["m.thread"] = json("latest_event" to latest, "count" to count, "current_user_participated" to participated)
            unsigned["m.relations"] = relations
            copy["unsigned"] = unsigned
            events[rootIndex] = copy
        }
        return id
    }

    private fun edit(room: String, sender: String, target: String, body: String) {
        event(
            room, sender, "m.room.message",
            json(
                "msgtype" to "m.text", "body" to "* $body",
                "m.new_content" to json("msgtype" to "m.text", "body" to body),
                "m.relates_to" to json("rel_type" to "m.replace", "event_id" to target),
            ),
        )
    }

    private fun react(room: String, target: String, sender: String, key: String) {
        event(room, sender, "m.reaction", json("m.relates_to" to json("rel_type" to "m.annotation", "event_id" to target, "key" to key)))
    }

    private fun redact(room: String, target: String, sender: String) {
        val events = rooms.getValue(room).events
        val index = events.indexOfFirst { it["event_id"] == target }
        if (index >= 0) {
            val copy = events[index].copy()
            copy["content"] = json()
            copy["unsigned"] = json(
                "redacted_because" to json("type" to "m.room.redaction", "sender" to sender, "content" to json("redacts" to target)),
            )
            events[index] = copy
        }
        event(room, sender, "m.room.redaction", json("redacts" to target), extra = json("redacts" to target))
    }

    private fun image(
        room: String,
        sender: String,
        name: String,
        hue: Double,
        width: Int = MockMedia.PHOTO_WIDTH,
        height: Int = MockMedia.PHOTO_HEIGHT,
        caption: String? = null,
    ): String {
        val data = MockMedia.photo(hue, width, height)
        // Подпись — это body, а имя файла уходит в `filename`: так Matrix отличает подпись от имени.
        val content = json(
            "msgtype" to "m.image", "body" to (caption ?: name), "url" to store(data, "image/png"),
            "info" to json("mimetype" to "image/png", "w" to width, "h" to height, "size" to data.size),
        )
        if (caption != null) content["filename"] = name
        return event(room, sender, "m.room.message", content)
    }

    private fun voice(room: String, sender: String) {
        val (data, waveform) = MockMedia.voice()
        event(
            room, sender, "m.room.message",
            json(
                "msgtype" to "m.audio", "body" to "Голосовое сообщение", "url" to store(data, "audio/wav"),
                "info" to json("mimetype" to "audio/wav", "duration" to 4_000, "size" to data.size),
                "org.matrix.msc1767.audio" to json("duration" to 4_000, "waveform" to waveform),
                "org.matrix.msc3245.voice" to json(),
            ),
        )
    }

    private fun file(room: String, sender: String, name: String, contents: String): String {
        val data = contents.toByteArray()
        return event(
            room, sender, "m.room.message",
            json(
                "msgtype" to "m.file", "body" to name, "url" to store(data, "text/plain"),
                "info" to json("mimetype" to "text/plain", "size" to data.size),
            ),
        )
    }

    private fun location(room: String, sender: String, body: String, lat: Double, lon: Double) {
        val uri = "geo:$lat,$lon"
        event(
            room, sender, "m.room.message",
            json("msgtype" to "m.location", "body" to body, "geo_uri" to uri, "org.matrix.msc3488.location" to json("uri" to uri)),
        )
    }

    private fun poll(room: String, sender: String, question: String, answers: List<String>): String {
        val options = answers.mapIndexed { index, answer -> json("id" to "a$index", "org.matrix.msc1767.text" to answer) }
        return event(
            room, sender, "org.matrix.msc3381.poll.start",
            json(
                "org.matrix.msc3381.poll.start" to json(
                    "question" to json("org.matrix.msc1767.text" to question),
                    "kind" to "org.matrix.msc3381.poll.disclosed",
                    "max_selections" to 1,
                    "answers" to options,
                ),
                "org.matrix.msc1767.text" to question,
            ),
        )
    }

    private fun vote(room: String, sender: String, poll: String, answers: List<String>) {
        event(
            room, sender, "org.matrix.msc3381.poll.response",
            json(
                "m.relates_to" to json("rel_type" to "m.reference", "event_id" to poll),
                "org.matrix.msc3381.poll.response" to json("answers" to answers),
            ),
        )
    }

    private fun drawStickers() {
        for (art in StickerArt.entries) {
            val data = art.png()
            stickers[art] = json(
                "url" to store(data, "image/png"), "body" to art.emoji,
                "info" to json("w" to StickerArt.SIDE, "h" to StickerArt.SIDE, "mimetype" to "image/png", "size" to data.size),
            )
        }
    }

    /** Картинки по коду — для `images` набора. */
    private fun pack(vararg arts: StickerArt): JSON = arts.associateTo(json()) { it.shortcode to stickers[it] }

    private fun sticker(room: String, sender: String, art: StickerArt) {
        val image = stickers[art] ?: return
        event(room, sender, "m.sticker", json("body" to (image["body"] ?: ""), "url" to (image["url"] ?: ""), "info" to (image["info"] ?: json())))
    }

    /** Отмечает комнату прочитанной до [index]-го сообщения: всё после — непрочитанное. */
    private fun read(room: String, user: String, index: Int) {
        val messages = rooms.getValue(room).events.filter { it["type"] == "m.room.message" }
        val message = messages.getOrNull(index) ?: return
        val id = message["event_id"].str() ?: return
        rooms.getValue(room).receipts[user] = MockRoom.Receipt(id, message["origin_server_ts"].long() ?: now)
    }

    private fun person(user: String, name: String, hue: Double?) {
        profiles[user] = MockProfile(name, hue?.let { store(MockMedia.avatar(it), "image/png") })
    }

    private fun memberContent(user: String, name: String? = null): JSON {
        val content = json("membership" to "join", "displayname" to (name ?: profiles[user]?.name ?: user))
        profiles[user]?.avatar?.let { content["avatar_url"] = it }
        return content
    }

    private fun store(data: ByteArray, mime: String): String {
        counter += 1
        val id = "seed$counter"
        media[id] = data to mime
        return "mxc://$SERVER/$id"
    }

    companion object {
        const val SERVER = MockHomeserver.SERVER_NAME

        /** Тип account data папок — `ChatFolders.type` на iOS. */
        const val CHAT_FOLDERS_TYPE = "moscow.swag.iskra.chat_folders"

        private const val SECOND = 1_000L
        private const val MINUTE = 60_000L

        fun user(name: String) = "@$name:$SERVER"

        val replies = listOf(
            "Ага, поняла 👌", "Звучит отлично!", "Ха-ха, точно", "Давай так и сделаем",
            "Секунду, сейчас посмотрю", "Спасибо!", "😄", "Договорились 🤝",
        )

        fun powerLevels(admins: List<String>): JSON = json(
            "users" to admins.associateWith { 100 }.toMutableMap(), "users_default" to 0, "events_default" to 0,
            "state_default" to 50, "ban" to 50, "kick" to 50, "redact" to 50, "invite" to 0,
            "events" to json(
                "m.room.name" to 50, "m.room.avatar" to 50, "m.room.topic" to 50,
                "m.room.pinned_events" to 50, "m.room.power_levels" to 100,
            ),
        )

        /** Правила уведомлений сервера по умолчанию, как их перечисляет спецификация. */
        fun pushRules(user: String): JSON {
            val notify = listOf<Any>("notify")
            val sound = listOf<Any>("notify", json("set_tweak" to "sound", "value" to "default"))
            val highlight = listOf<Any>("notify", json("set_tweak" to "sound", "value" to "default"), json("set_tweak" to "highlight"))
            fun event(key: String, pattern: String) = json("kind" to "event_match", "key" to key, "pattern" to pattern)
            fun rule(id: String, actions: List<Any>, conditions: List<JSON>, enabled: Boolean = true) = json(
                "rule_id" to ".m.rule.$id", "default" to true, "enabled" to enabled, "actions" to actions, "conditions" to conditions,
            )
            val localpart = user.drop(1).substringBefore(':')
            return json(
                "global" to json(
                    "override" to mutableListOf<Any?>(
                        rule("master", emptyList(), emptyList(), enabled = false),
                        rule("suppress_notices", emptyList(), listOf(event("content.msgtype", "m.notice"))),
                        rule(
                            "invite_for_me", sound,
                            listOf(event("type", "m.room.member"), event("content.membership", "invite"), event("state_key", user)),
                        ),
                        rule("member_event", emptyList(), listOf(event("type", "m.room.member"))),
                        rule(
                            "is_user_mention", highlight,
                            listOf(json("kind" to "event_property_contains", "key" to "content.m\\.mentions.user_ids", "value" to user)),
                        ),
                        rule(
                            "is_room_mention", highlight,
                            listOf(
                                json("kind" to "event_property_is", "key" to "content.m\\.mentions.room", "value" to true),
                                json("kind" to "sender_notification_permission", "key" to "room"),
                            ),
                        ),
                        rule("tombstone", highlight, listOf(event("type", "m.room.tombstone"), event("state_key", ""))),
                        rule("reaction", emptyList(), listOf(event("type", "m.reaction"))),
                        rule(
                            "suppress_edits", emptyList(),
                            listOf(json("kind" to "event_property_is", "key" to "content.m\\.relates_to.rel_type", "value" to "m.replace")),
                        ),
                    ),
                    "content" to mutableListOf<Any?>(
                        json(
                            "rule_id" to ".m.rule.contains_user_name", "default" to true, "enabled" to true,
                            "pattern" to localpart, "actions" to highlight,
                        ),
                    ),
                    "room" to mutableListOf<Any?>(), "sender" to mutableListOf<Any?>(),
                    "underride" to mutableListOf<Any?>(
                        rule("call", listOf("notify", json("set_tweak" to "sound", "value" to "ring")), listOf(event("type", "m.call.invite"))),
                        rule(
                            "encrypted_room_one_to_one", sound,
                            listOf(json("kind" to "room_member_count", "is" to "2"), event("type", "m.room.encrypted")),
                        ),
                        rule(
                            "room_one_to_one", sound,
                            listOf(json("kind" to "room_member_count", "is" to "2"), event("type", "m.room.message")),
                        ),
                        rule("message", notify, listOf(event("type", "m.room.message"))),
                        rule("encrypted", notify, listOf(event("type", "m.room.encrypted"))),
                    ),
                ),
            )
        }
    }
}
