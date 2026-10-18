package wtf.iskra.core.timeline

import java.text.Normalizer

/** Кого можно позвать через «@»: участник комнаты, как его зовут и как он выглядит. */
data class MentionCandidate(val userId: String, val name: String, val avatarUrl: String?)

/**
 * Позванный из списка «@» в композере — кто и под каким именем он лёг в текст. Поле ввода держит
 * простые слова, поэтому упоминание помнится рядом с черновиком, а не в нём: `@Kai Guo` в тексте и эта
 * запись о том, какой это Kai. При отправке они сводятся снова (`ComposerMentions.markdown`): имя,
 * которое человек стёр или перепечатал, упоминанием больше не является — правда в тексте.
 */
data class ComposerMention(val userId: String, val name: String) {
    /** Что ложится в поле и что ищется при отправке. */
    val token: String get() = "@$name"
}

/** «@», который набирается сейчас: где он в черновике (от «@» до курсора) и что после него. */
data class MentionQuery(val start: Int, val end: Int, val text: String)

/**
 * Арифметика списка «@» — порт `ComposerMentions.swift`: найти запрос, выбрать, кто под него
 * подходит, вставить имя и превратить выбранное в то, чем упоминание является в Matrix. Без экранов и
 * без SDK — всё спрашивается в тестах.
 */
object ComposerMentions {
    /** Длиннее — уже не запрос: сорок букв имени, чтобы найти его в списке, не набирает никто. */
    private const val LONGEST_QUERY = 32

    /**
     * «@…», на конце которого стоит курсор. «@» считается только в начале или после пробела и
     * открывающей скобки — `boris@mail.ru` адрес, а не зов. Запрос кончается на пробеле.
     */
    fun query(text: String, cursor: Int): MentionQuery? {
        if (cursor > text.length || cursor < 0) return null
        val before = text.substring(0, cursor)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        val typed = before.substring(at + 1)
        if (typed.codePointCount(0, typed.length) > LONGEST_QUERY || typed.any { it.isWhitespace() || it == '@' }) return null
        if (at > 0) {
            val previous = text[at - 1]
            if (!previous.isWhitespace() && previous !in OPENERS) return null
        }
        return MentionQuery(at, cursor, typed)
    }

    /**
     * Кого запрос может значить, лучшие первыми. Совпадение — начало любого слова имени или начало
     * своей части Matrix ID; регистр и диакритика не считаются: «@ё» находит Ёжика, «@e» — Émile.
     */
    fun matches(query: String, members: List<MentionCandidate>): List<MentionCandidate> {
        val needle = fold(query)
        if (needle.isEmpty()) return members
        return members.filter { member ->
            val words = fold(member.name).split(' ', '\t', '-', '.').filter { it.isNotEmpty() }
            words.any { it.startsWith(needle) } || fold(localpart(member.userId)).startsWith(needle)
        }
    }

    /** Черновик с именем вместо запроса и куда встаёт курсор. За именем — пробел, если его там нет. */
    fun insert(member: MentionCandidate, query: MentionQuery, text: String): Triple<String, Int, ComposerMention> {
        val mention = ComposerMention(member.userId, member.name)
        val head = text.substring(0, query.start)
        var tail = text.substring(query.end)
        if (tail.firstOrNull()?.isWhitespace() != true) tail = " $tail"
        val result = head + mention.token + tail
        return Triple(result, head.length + mention.token.length + 1, mention)
    }

    /**
     * Черновик как Markdown, где каждое уцелевшее упоминание — ссылка `matrix.to`, и ID уцелевших —
     * для `m.mentions`, который и будит. Нужны оба: ссылка без набора не будит никого на нынешнем
     * сервере, набор без ссылки показывает имя, на которое не нажать.
     *
     * Упоминание уцелело, только где его `@Имя` в тексте целиком и не начало длинного слова: `@Ann`
     * не находится внутри `@Anna`. Длинные имена ставятся первыми по той же причине.
     */
    fun markdown(text: String, mentions: List<ComposerMention>): Pair<String, List<String>> {
        val unique = mentions.distinct().sortedByDescending { it.name.length }
        if (unique.isEmpty()) return text to emptyList()

        val found = mutableListOf<Pair<IntRange, ComposerMention>>()
        for (mention in unique) {
            var start = 0
            while (true) {
                val at = text.indexOf(mention.token, start)
                if (at < 0) break
                val end = at + mention.token.length
                start = end
                if (end < text.length && isWordCharacter(text[end])) continue
                if (at > 0 && isWordCharacter(text[at - 1])) continue
                val range = at until end
                if (found.any { it.first.first < range.last + 1 && range.first < it.first.last + 1 }) continue
                found.add(range to mention)
            }
        }
        if (found.isEmpty()) return text to emptyList()

        found.sortBy { it.first.first }
        val result = StringBuilder()
        var cursor = 0
        val userIds = mutableListOf<String>()
        for ((range, mention) in found) {
            result.append(text, cursor, range.first)
            result.append('[').append(escaped(mention.token)).append("](").append(MATRIX_TO).append(mention.userId).append(')')
            cursor = range.last + 1
            if (mention.userId !in userIds) userIds.add(mention.userId)
        }
        result.append(text, cursor, text.length)
        return result.toString() to userIds
    }

    /** Обратно: своё сообщение — в композер для правки, `[@Kai](https://matrix.to/#/@kai:…)` снова `@Kai`. */
    fun unlinked(markdown: String): Pair<String, List<ComposerMention>> {
        val text = StringBuilder()
        val mentions = mutableListOf<ComposerMention>()
        var cursor = 0
        for (match in LINK.findAll(markdown)) {
            val token = unescaped(match.groupValues[1])
            if (!token.startsWith("@") || token.length < 2) continue
            text.append(markdown, cursor, match.range.first)
            text.append(token)
            cursor = match.range.last + 1
            val mention = ComposerMention(match.groupValues[2], token.drop(1))
            if (mention !in mentions) mentions.add(mention)
        }
        text.append(markdown, cursor, markdown.length)
        return text.toString() to mentions
    }

    /** «kai» из «@kai:matrix.org». */
    fun localpart(userId: String): String = userId.removePrefix("@").substringBefore(':')

    private fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    private fun isWordCharacter(c: Char) = c.isLetterOrDigit() || c == '_'

    /** Имя — слова человека, и Markdown прочёл бы `*`, `_` или `]` в нём как разметку. */
    private fun escaped(text: String) = buildString { text.forEach { if (it in MARKUP) append('\\'); append(it) } }

    private fun unescaped(text: String) = buildString {
        var escaping = false
        for (c in text) {
            if (!escaping && c == '\\') {
                escaping = true
                continue
            }
            escaping = false
            append(c)
        }
    }

    private const val OPENERS = "([{«\"'"
    private const val MARKUP = "\\`*_[]()<>#~|!"
    private const val MATRIX_TO = "https://matrix.to/#/"
    private val MARKS = Regex("\\p{Mn}+")
    private val LINK = Regex("""\[((?:\\.|[^\]\\])+)\]\(https://matrix\.to/#/(@[^)\s]+)\)""")
}
