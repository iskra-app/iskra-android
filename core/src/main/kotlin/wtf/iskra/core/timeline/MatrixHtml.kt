package wtf.iskra.core.timeline

import java.net.URLDecoder

/** Как выглядит отрезок текста. Слово за стиль решает экран; здесь — только что это за отрезок. */
data class RichStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
    val code: Boolean = false,
    val underline: Boolean = false,
    /** Адрес ссылки — только `http(s)`, `mailto`, `matrix`. */
    val link: String? = null,
    /** Упоминание — Matrix ID, на который указывает «пилюля» `matrix.to`. */
    val mentionUserId: String? = null,
    val mentionIsSelf: Boolean = false,
    /** Спойлер: номер, чтобы каждый раскрывался своим нажатием. */
    val spoiler: Int? = null,
    val quote: Boolean = false,
    val heading: Int? = null,
)

data class RichSpan(val start: Int, val end: Int, val style: RichStyle)

/** Текст пузыря: слова и отрезки со стилем. Строка — чтобы экран мог её показать даже без стилей. */
data class RichText(val text: String, val spans: List<RichSpan> = emptyList()) {
    companion object {
        /**
         * Пузырь сообщения: из HTML, если в нём есть слова, иначе — простой `body`. И голые адреса
         * становятся ссылками и там, и там — `https://…` в тексте без разметки всё равно ссылка.
         */
        fun of(body: String, formattedBody: String?, ownUserId: String? = null): RichText {
            val parsed = formattedBody?.let { MatrixHtml.parse(it, ownUserId) } ?: RichText(body)
            return linkified(parsed)
        }

        private val BARE_URL = Regex("""https?://[^\s<>"]+[^\s<>".,;:!?)\]}'»]""")

        private fun linkified(rich: RichText): RichText {
            val extra = BARE_URL.findAll(rich.text).mapNotNull { match ->
                val start = match.range.first
                val end = match.range.last + 1
                val covered = rich.spans.any { it.start < end && start < it.end && (it.style.link != null || it.style.mentionUserId != null || it.style.code) }
                if (covered) null else RichSpan(start, end, RichStyle(link = match.value))
            }.toList()
            return if (extra.isEmpty()) rich else rich.copy(spans = rich.spans + extra)
        }
    }
}

/**
 * `formatted_body` сообщения — HTML, который Matrix несёт рядом с простым телом, — в [RichText].
 * Порт `MatrixHTML.swift` (docs/PROMPT.md, 12.3).
 *
 * **Свой парсер**, не `Html.fromHtml` и не WebView: тот пошёл бы за `<img src>` туда, куда указал
 * отправитель, — из зашифрованной переписки. Разрешённого в сообщении — короткий список тегов, а такой
 * список — это токенизатор и стек.
 *
 * Главное правило — **слова выживают всегда**: непонятный тег выбрасывается, его текст остаётся;
 * картинка — её `alt`. Целиком выбрасывается только `<mx-reply>`: цитата для клиентов без ответов.
 * Линейный: отправитель не может заставить его работать квадратично.
 */
object MatrixHtml {
    private val ALLOWED_SCHEMES = setOf("http", "https", "mailto", "matrix")
    private const val MAXIMUM_DEPTH = 64
    private const val MAXIMUM_LIST_NUMBER = 1_000_000

    /** Текст или null для HTML без слов — тогда вызывающий рисует простое тело. */
    fun parse(html: String, ownUserId: String? = null): RichText? {
        val parser = Parser(ownUserId)
        parser.run(html)
        val result = parser.finish()
        return if (result.text.any { !it.isWhitespace() }) result else null
    }

    /** Matrix ID, который называет ссылка `matrix.to`, когда она называет человека. */
    fun mentionedUser(href: String): String? {
        val prefix = listOf("https://matrix.to/#/", "http://matrix.to/#/").firstOrNull { href.startsWith(it) }
        if (prefix == null) {
            if (href.startsWith("matrix:u/")) {
                val id = href.removePrefix("matrix:u/").substringBefore('?')
                return if (id.isEmpty()) null else "@$id"
            }
            return null
        }
        val rest = href.removePrefix(prefix).substringBefore('?')
        val decoded = runCatching { URLDecoder.decode(rest.replace("+", "%2B"), "UTF-8") }.getOrDefault(rest)
        return if (decoded.startsWith("@") && ':' in decoded) decoded else null
    }

    private class Element(val name: String) {
        var bold = false
        var italic = false
        var strike = false
        var code = false
        var underline = false
        var link: String? = null
        var mention: String? = null
        var spoiler: Int? = null
        var quote = false
        var heading: Int? = null
        var preformatted = false
    }

    private class Parser(private val ownUserId: String?) {
        private val output = StringBuilder()
        private val spans = mutableListOf<RichSpan>()
        private val stack = mutableListOf<Element>()
        private var skipping = 0
        private val lists = mutableListOf<Int?>()
        private var spoilers = 0

        fun run(html: String) {
            var index = 0
            val text = StringBuilder()
            // Линейно: если для `<` нет `>`, его не будет и для следующих — дальше одни слова.
            var tagsPossible = true
            while (index < html.length) {
                val character = html[index]
                if (character == '<' && tagsPossible) {
                    val close = html.indexOf('>', index)
                    if (close < 0) {
                        tagsPossible = false
                        continue
                    }
                    if (text.isNotEmpty()) {
                        append(decodeEntities(text.toString()))
                        text.clear()
                    }
                    tag(html.substring(index + 1, close))
                    index = close + 1
                } else {
                    text.append(character)
                    index += 1
                }
            }
            if (text.isNotEmpty()) append(decodeEntities(text.toString()))
        }

        fun finish(): RichText {
            // Пустые строки в хвосте от последнего абзаца: пузырь кончается последним словом.
            while (output.isNotEmpty() && output.last().isWhitespace()) removeLast()
            return RichText(output.toString(), spans.filter { it.end > it.start })
        }

        private fun removeLast() {
            output.setLength(output.length - 1)
            val length = output.length
            for (i in spans.indices.reversed()) {
                val span = spans[i]
                if (span.end > length) {
                    if (span.start >= length) spans.removeAt(i) else spans[i] = span.copy(end = length)
                }
            }
        }

        private fun tag(raw: String) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("?")) return
            if (trimmed.startsWith("/")) {
                close(trimmed.drop(1).trim().lowercase())
                return
            }
            val selfClosing = trimmed.endsWith("/")
            val body = if (selfClosing) trimmed.dropLast(1) else trimmed
            val name = body.takeWhile { !it.isWhitespace() }.lowercase()
            val attributes = attributes(body.drop(name.length))

            if (name == "mx-reply") {
                if (!selfClosing) skipping += 1
                return
            }
            if (skipping > 0) return

            when (name) {
                "br" -> {
                    appendRaw("\n")
                    return
                }
                "hr" -> {
                    breakBlock()
                    appendRaw("———")
                    breakBlock()
                    return
                }
                "img" -> {
                    // Никогда не скачивается. Своё эмодзи — это его «:name:» в alt.
                    (attributes["alt"] ?: attributes["title"])?.takeIf { it.isNotEmpty() }?.let(::append)
                    return
                }
            }
            // Глубже этого ничего нового не открывается — слова проходят без стиля.
            if (selfClosing || stack.size >= MAXIMUM_DEPTH) return

            val element = Element(name)
            when (name) {
                "b", "strong" -> element.bold = true
                "i", "em", "cite" -> element.italic = true
                "s", "del", "strike" -> element.strike = true
                "u", "ins" -> element.underline = true
                "code" -> element.code = true
                "pre" -> {
                    breakBlock()
                    element.code = true
                    element.preformatted = true
                }
                "a" -> {
                    val href = attributes["href"].orEmpty()
                    val user = mentionedUser(href)
                    if (user != null) {
                        element.mention = user
                    } else {
                        val scheme = href.substringBefore(':', "").lowercase()
                        if (scheme in ALLOWED_SCHEMES && href.length > scheme.length + 1) element.link = href
                    }
                }
                "span", "font" -> if (attributes.containsKey("data-mx-spoiler")) {
                    element.spoiler = spoilers
                    spoilers += 1
                }
                // Абзац внутри пункта списка — текст пункта, а не свой блок.
                "p", "div" -> if (stack.none { it.name == "li" }) breakBlock(blank = true)
                "blockquote" -> {
                    breakBlock(blank = true)
                    element.quote = true
                }
                "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    breakBlock(blank = true)
                    element.heading = name.drop(1).toIntOrNull() ?: 1
                    element.bold = true
                }
                "ul" -> {
                    breakBlock()
                    lists += null
                }
                "ol" -> {
                    breakBlock()
                    // Зажато: `start` пишет отправитель, а Int.MAX + 1 — ловушка, а не число.
                    val start = attributes["start"]?.toLongOrNull() ?: 1L
                    lists += start.coerceIn(0L, MAXIMUM_LIST_NUMBER.toLong()).toInt()
                }
                "li" -> {
                    breakBlock()
                    val indent = "    ".repeat(maxOf(0, lists.size - 1))
                    val number = lists.lastOrNull()
                    if (lists.isNotEmpty() && number != null) {
                        appendRaw("$indent$number. ")
                        lists[lists.lastIndex] = minOf(number + 1, MAXIMUM_LIST_NUMBER)
                    } else {
                        appendRaw("$indent• ")
                    }
                }
            }
            stack += element
        }

        private fun close(name: String) {
            if (name == "mx-reply") {
                skipping = maxOf(0, skipping - 1)
                return
            }
            if (skipping > 0) return
            // До парного элемента — чтобы заблудший тег не оставил жирный до конца сообщения.
            val position = stack.indexOfLast { it.name == name }
            if (position < 0) return
            while (stack.size > position) stack.removeAt(stack.lastIndex)

            when (name) {
                "p", "div" -> if (stack.any { it.name == "li" }) breakBlock() else breakBlock(blank = true)
                "blockquote", "h1", "h2", "h3", "h4", "h5", "h6" -> breakBlock(blank = true)
                "pre", "li" -> breakBlock()
                "ul", "ol" -> {
                    if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
                    breakBlock()
                }
            }
        }

        private val isPreformatted get() = stack.any { it.preformatted }

        private fun append(raw: String) {
            if (skipping > 0) return
            var text = raw
            if (!isPreformatted) {
                // Правило HTML: любой пробельный отрезок — один пробел, и никакого в начале строки.
                text = WHITESPACE.replace(text, " ")
                if (output.isEmpty() || output.last() == '\n') text = text.trimStart(' ')
                if (text.startsWith(" ") && output.isNotEmpty() && output.last() == ' ') text = text.drop(1)
            }
            if (text.isEmpty()) return

            // Черта цитаты — перед каждой её строкой.
            if (stack.any { it.quote }) {
                if (output.isEmpty() || output.last() == '\n') appendRaw(QUOTE_BAR, quote = true)
                text = text.replace("\n", "\n$QUOTE_BAR")
            }

            var style = RichStyle()
            for (element in stack) {
                style = style.copy(
                    bold = style.bold || element.bold,
                    italic = style.italic || element.italic,
                    strikethrough = style.strikethrough || element.strike,
                    code = style.code || element.code,
                    underline = style.underline || element.underline,
                    link = element.link ?: style.link,
                    mentionUserId = element.mention ?: style.mentionUserId,
                    mentionIsSelf = if (element.mention != null) element.mention == ownUserId else style.mentionIsSelf,
                    spoiler = element.spoiler ?: style.spoiler,
                    quote = style.quote || element.quote,
                    heading = element.heading ?: style.heading,
                )
            }
            val start = output.length
            output.append(text)
            if (style != RichStyle()) spans += RichSpan(start, output.length, style)
        }

        private fun appendRaw(text: String, quote: Boolean = false) {
            val start = output.length
            output.append(text)
            if (quote) spans += RichSpan(start, output.length, RichStyle(quote = true))
        }

        /** Заканчивает строку, если не закончена; с [blank] — оставляет пустую после неё. */
        private fun breakBlock(blank: Boolean = false) {
            if (output.isEmpty()) return
            if (output.last() == ' ') removeLast()
            if (output.isEmpty()) return
            if (output.last() != '\n') appendRaw("\n")
            if (blank && !(output.length >= 2 && output[output.length - 1] == '\n' && output[output.length - 2] == '\n')) appendRaw("\n")
        }

        companion object {
            private val WHITESPACE = Regex("\\s+")
            private const val QUOTE_BAR = "▍"
            private val ATTRIBUTE = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")

            /** `name="value"`, `name='value'`, `name=value` и голое `name`; имя — в нижнем регистре. */
            fun attributes(source: String): Map<String, String> = ATTRIBUTE.findAll(source).associate { match ->
                val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value
                match.groupValues[1].lowercase() to (value?.let(::decodeEntities) ?: "")
            }

            fun decodeEntities(text: String): String {
                if ('&' !in text) return text
                val result = StringBuilder()
                var index = 0
                while (index < text.length) {
                    if (text[index] == '&') {
                        val semicolon = text.indexOf(';', index)
                        if (semicolon in (index + 1)..(index + ENTITY_MAX)) {
                            val decoded = entity(text.substring(index + 1, semicolon))
                            if (decoded != null) {
                                result.append(decoded)
                                index = semicolon + 1
                                continue
                            }
                        }
                    }
                    result.append(text[index])
                    index += 1
                }
                return result.toString()
            }

            private const val ENTITY_MAX = 11

            private fun entity(name: String): String? = when (name) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else -> {
                    val code = when {
                        name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)
                        name.startsWith("#") -> name.drop(1).toIntOrNull()
                        else -> null
                    }
                    code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }
                }
            }
        }
    }
}
