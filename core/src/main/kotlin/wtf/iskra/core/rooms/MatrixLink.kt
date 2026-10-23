package wtf.iskra.core.rooms

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Указатель на человека или комнату в том написании, в каком он пришёл. Порт `MatrixLink`.
 *
 * **Пишется всегда ссылка `s.iskra.wtf`** — в QR-коде, за «Поделиться». Это обычный веб-адрес, поэтому камера телефона
 * открывает его, ничего не устанавливая: страница там говорит, чей он, и предлагает Искру или любой другой клиент
 * Matrix. `matrix.to` делал ту же работу, но он не наш: его ссылки не открываются в этом приложении напрямую, а его
 * страница предлагает всех, кроме него. `matrix:` — собственная схема протокола и лучше устроена, но камера, встретив её
 * без приложения, что на неё отвечает, ничего не покажет.
 *
 * **Читается всё**: `s.iskra.wtf` и `matrix.to`, с `?via=` и без, `matrix:u/…`, `matrix:r/…`, `matrix:roomid/…`, те же три
 * после `wtf.iskra://` (кнопка на странице `s.iskra.wtf`) и голые `@человек:сервер`, `#комната:сервер`, `!id` — потому
 * что QR-код или ссылку из другого места сделало чужое приложение, и это то, что оно выбрало.
 */
sealed interface MatrixLink {
    /** Человек, по Matrix ID. */
    data class User(val userId: String) : MatrixLink

    /** Комната по псевдониму (`#имя:сервер`) или ID (`!id`), и серверы, через которые спрашивать, пока нашего в ней нет. */
    data class Room(val address: String, val via: List<String> = emptyList()) : MatrixLink

    private val target: String get() = when (this) {
        is User -> userId
        is Room -> address
    }

    private val servers: List<String> get() = (this as? Room)?.via.orEmpty()

    /** Ссылка `s.iskra.wtf`: `/@boris:matrix.org`, `/%23iskra:matrix.org`, `/!abc:matrix.org?via=matrix.org`. */
    val url: String
        get() = "https://$WEB_HOST/" + escapePath(target) + query(servers)

    /**
     * То же, но прямо в приложение — `wtf.iskra://u/boris:matrix.org`, `wtf.iskra://r/iskra:matrix.org`,
     * `wtf.iskra://roomid/abc:matrix.org?via=…`: её открывает кнопка «Открыть в Искре» на странице `s.iskra.wtf`. Слова вместо
     * знаков — как в MSC2312, чтобы две записи читались одинаково.
     */
    val deepLink: String
        get() {
            val kind = when (target.first()) {
                '@' -> "u"
                '#' -> "r"
                else -> "roomid"
            }
            return "wtf.iskra://$kind/" + escapePath(target.drop(1)) + query(servers)
        }

    companion object {
        /** Хост страницы-ссылки: единственный веб-адрес, который приложение читает как свой. */
        const val WEB_HOST = "s.iskra.wtf"

        /** Читает ссылку, URI или голый идентификатор. Null — всё, что не оно: QR из меню, пароль от Wi-Fi, ссылка в другое место. */
        fun parse(text: String): MatrixLink? {
            val trimmed = text.trim()
            val scheme = schemeOf(trimmed)
            when (scheme) {
                "https", "http" -> {
                    val rest = trimmed.substring(scheme.length + 3).takeIf { trimmed.startsWith("$scheme://", ignoreCase = true) } ?: return null
                    val host = rest.takeWhile { it != '/' && it != '?' && it != '#' }.lowercase()
                    val after = rest.drop(host.length)
                    return when (host) {
                        "matrix.to" -> {
                            // `#/@boris:matrix.org` — фрагмент без ведущего слэша — идентификатор, а за ним, может быть, ID события и запрос.
                            val fragment = decode(after.substringAfter('#', missingDelimiterValue = "").ifEmpty { return null })
                            identifier(fragment.removePrefix("/"))
                        }
                        WEB_HOST -> webLink(after)
                        else -> null
                    }
                }
                "matrix" -> return msc2312(trimmed.drop("matrix:".length))
                "wtf.iskra" -> {
                    // Своя схема несёт и другое — обратный вызов входа, передачу из системного «Поделиться», и всё это пишется без
                    // хоста. Ссылка на человека или комнату — та, где `//` и одно из трёх слов MSC2312.
                    val prefix = "wtf.iskra://"
                    if (!trimmed.lowercase().startsWith(prefix)) return null
                    return msc2312(trimmed.drop(prefix.length))
                }
            }
            return identifier(trimmed)
        }

        /** `@x:y`, `#x:y`, `!x[:y]`, и, может быть, `/событие` и `?via=…` следом. */
        private fun identifier(text: String): MatrixLink? {
            val (head, query) = split(text, '?')
            // `/$событие` после комнаты — указатель на одно сообщение; комната — то, где оно лежит, и открывается комната.
            val id = head.substringBefore('/')
            return when (id.firstOrNull()) {
                '@' -> if (isUserId(id)) User(id) else null
                '#', '!' -> if (Conversations.isRoomAddress(id)) Room(id, via(query)) else null
                else -> null
            }
        }

        /**
         * `https://s.iskra.wtf/@boris:matrix.org`, `/%23iskra:matrix.org`, `/!abc:matrix.org?via=…` — идентификатор в пути, как его
         * пишет [url]. Страница отвечает и на идентификатор во фрагменте: `matrix.to` с подменённым хостом работает — `/#/@boris:matrix.org`,
         * и `/#iskra:matrix.org`, где `#`, начинающий фрагмент, — собственный знак псевдонима.
         */
        private fun webLink(after: String): MatrixLink? {
            val beforeFragment = after.substringBefore('#')
            val fragment = after.substringAfter('#', missingDelimiterValue = "")
            val (path, query) = split(beforeFragment, '?')
            if (path.length > 1) return identifier(decode(path.drop(1)) + (query?.let { "?$it" } ?: ""))
            val decoded = decode(fragment)
            if (decoded.isEmpty()) return null
            return when {
                decoded.startsWith("/") -> identifier(decoded.drop(1))
                decoded.first() in "@#!" -> identifier(decoded)
                else -> identifier("#$decoded")
            }
        }

        /** `u/boris:matrix.org`, `r/комната:сервер`, `roomid/abc:matrix.org?via=…` — MSC2312, после `matrix:` или `wtf.iskra://`. */
        private fun msc2312(body: String): MatrixLink? {
            val (path, query) = split(body, '?')
            val parts = path.split('/').filter { it.isNotEmpty() }.map(::decode)
            if (parts.size < 2) return null
            return when (parts[0]) {
                "u" -> ("@" + parts[1]).takeIf(::isUserId)?.let(::User)
                "r" -> ("#" + parts[1]).takeIf(Conversations::isRoomAddress)?.let { Room(it, via(query)) }
                "roomid" -> ("!" + parts[1]).takeIf(Conversations::isRoomAddress)?.let { Room(it, via(query)) }
                else -> null
            }
        }

        private fun schemeOf(text: String): String? {
            val colon = text.indexOf(':')
            if (colon <= 0) return null
            val scheme = text.substring(0, colon)
            // Схема — буквы, цифры, плюс, точка, дефис; `@boris:matrix.org` и `#x:y` — не она.
            if (!scheme[0].isLetter() || !scheme.all { it.isLetterOrDigit() || it in "+.-" }) return null
            return scheme.lowercase()
        }

        private fun split(text: String, separator: Char): Pair<String, String?> {
            val index = text.indexOf(separator)
            return if (index < 0) text to null else text.substring(0, index) to text.substring(index + 1)
        }

        private fun via(query: String?): List<String> = query.orEmpty().split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2 && parts[0] == "via") decode(parts[1]) else null
        }

        private fun isUserId(text: String) = RoomModeration.isMatrixId(text) && ' ' !in text

        /** `+` в пути — плюс, а не пробел: `URLDecoder` читает его как форму, и его бережём. */
        private fun decode(text: String): String = runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }.getOrDefault(text)

        /** В пути `#` закончил бы его и начал фрагмент, поэтому экранируется; `:`, `@` и `!` — как есть. */
        private fun escapePath(text: String): String = buildString {
            for (char in text) {
                if (char.isLetterOrDigit() || char in "-._~:@!$'()*,=") append(char) else append(URLEncoder.encode(char.toString(), "UTF-8").replace("+", "%20"))
            }
        }

        private fun query(via: List<String>): String =
            if (via.isEmpty()) "" else "?" + via.joinToString("&") { "via=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    }
}
