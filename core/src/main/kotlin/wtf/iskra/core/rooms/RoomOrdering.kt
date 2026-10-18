package wtf.iskra.core.rooms

/**
 * Порядок строк списка — чистые функции, чтобы правило можно было закрепить тестом без домашнего
 * сервера. Порт `RoomListStore.byRecency` и `partitionArchived`.
 */
object RoomOrdering {
    /**
     * Один список на входе, два на выходе: архив — всё, что человек смахнул в сторону, главный
     * список — остальное. Фильтр по `m.lowpriority` здесь, а не в SDK: фильтр SDK выбросил бы эти
     * комнаты из потока диффов, и экрану архива нечего было бы показать.
     */
    fun partitionArchived(summaries: List<RoomSummary>): Pair<List<RoomSummary>, List<RoomSummary>> =
        summaries.partition { !it.isArchived }

    /**
     * Новые сверху — сортировка здесь, а не порядок SDK.
     *
     * Обычно они совпадают, а где расходятся, SDK отвечает на другой вопрос: его порядок — то, что
     * знает *сервер*, а сообщение в очереди отправки сервер не знает по определению. Неотправленное
     * сдвигало время и текст строки (`latestEvent()` считает локальное эхо), а саму строку оставляло
     * на месте: порядок и время рядом описывали два разных списка. Сортировка по `lastActivity`
     * делает место строки и время на ней одним фактом.
     *
     * Ничьи сохраняют порядок SDK (сортировка устойчивая), чтобы пачка комнат с одним временем не
     * перетасовывалась на каждой пересборке; комната без единого сообщения тонет вниз.
     */
    fun byRecency(summaries: List<RoomSummary>): List<RoomSummary> = summaries.sortedWith(RECENCY)

    private val RECENCY = Comparator<RoomSummary> { left, right ->
        when {
            // Приглашения первыми, и это не украшение: у приглашения нет сообщений, значит нет
            // `lastActivity`, и сортировка ниже утопила бы его под всеми разговорами. Единственная
            // строка, которая задаёт вопрос, оказалась бы последней, что человек видит.
            left.isInvitation != right.isInvitation -> if (left.isInvitation) -1 else 1
            // Закреплённые — над остальными, но под приглашениями.
            left.isPinned != right.isPinned -> if (left.isPinned) -1 else 1
            else -> {
                val l = left.lastActivity
                val r = right.lastActivity
                when {
                    l != null && r != null -> r.compareTo(l)
                    l == null && r != null -> 1
                    l != null && r == null -> -1
                    else -> 0
                }
            }
        }
    }
}
