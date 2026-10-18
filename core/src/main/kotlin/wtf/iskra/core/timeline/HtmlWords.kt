package wtf.iskra.core.timeline

/**
 * Слова отформатированного сообщения без разметки — одной строкой, для списка чатов и экрана
 * блокировки.
 *
 * Сообщение в Markdown несёт его дважды: HTML и сам Markdown в простом `body`, запасном для
 * клиентов без форматирования. Строка списка не может быть жирной, но может хотя бы не быть уроком
 * синтаксиса (`**важно**`), поэтому, где есть HTML, слова читаются из него.
 *
 * Это не `MatrixHTML` (полный разбор для пузыря придёт с этапом 4) — только слова: теги выброшены,
 * их текст оставлен, `<mx-reply>` выброшен целиком (цитата, которую ответ тащит для старых клиентов),
 * сущности раскрыты. Не `android.text.Html`: ему нужен Android, а этот код проверяется на JVM.
 */
object HtmlWords {
    /** Слова, или null, если в HTML их нет, — тогда вызывающий берёт простой `body`. */
    fun plain(html: String): String? {
        val withoutReply = REPLY.replace(html, "")
        val spaced = BREAKS.replace(withoutReply, " ")
        val text = decode(TAG.replace(spaced, ""))
        val collapsed = WHITESPACE.replace(text, " ").trim()
        return collapsed.ifEmpty { null }
    }

    /** Слова сообщения: из HTML, если он есть и в нём есть слова, иначе — сам `body`. */
    fun words(body: String, html: String?): String = html?.let(::plain) ?: body

    private val REPLY = Regex("<mx-reply>.*?</mx-reply>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val BREAKS = Regex("<br\\s*/?>|</(p|div|li|h[1-6]|blockquote|pre|tr)>", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

    private fun decode(text: String): String = ENTITY.replace(text) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(radix = 16)
            name.startsWith("#") -> name.drop(1).toIntOrNull()
            else -> return@replace NAMED[name] ?: match.value
        }
        if (code == null || !Character.isValidCodePoint(code)) match.value else String(Character.toChars(code))
    }
}
