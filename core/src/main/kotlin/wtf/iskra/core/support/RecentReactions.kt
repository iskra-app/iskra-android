package wtf.iskra.core.support

/**
 * Эмодзи, за которыми человек тянулся недавно, — чтобы второй раз было быстрее первого. Порт `RecentEmoji`.
 *
 * Одна строка через пробелы — весь формат хранения: реакция — символ-другой, список — полторы строки
 * панели, и пробел никогда не стоит *внутри* эмодзи. Новые первыми, без повторов: двадцатый 👍 не
 * должен вытолкнуть из списка всё остальное.
 */
object RecentReactions {
    /** Двенадцать: полоса из шести над меню, дважды, и рисуется двумя рядами по шесть. */
    const val LIMIT = 12

    fun list(stored: String): List<String> = stored.split(' ').filter { it.isNotEmpty() }

    fun adding(emoji: String, stored: String): String =
        (listOf(emoji) + list(stored).filter { it != emoji }).take(LIMIT).joinToString(" ")
}
