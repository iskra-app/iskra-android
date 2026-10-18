package wtf.iskra.core.timeline

import java.time.Duration
import java.time.Instant

/**
 * Чем стал один элемент SDK до встречи с соседями. Всё здесь — функция одного элемента; проходы,
 * которым нужны соседи (заходы, схлопнутые серые строки, «прочитано», полоса непрочитанного), — над
 * этим и без SDK. Порт `TimelineMapper.Mapped` и чистой половины `TimelineMapper.swift`.
 */
internal data class Mapped(
    val body: Body,
    /** Кто-то, кроме нас, поставил квитанцию на этот элемент. */
    val hasForeignReceipt: Boolean = false,
    val receiptReadAt: Instant? = null,
    /** ID ответа, оригинала которого SDK не держит, — его надо попросить. */
    val unresolvedReply: String? = null,
) {
    sealed interface Body {
        data class DayDividerBody(val divider: DayDivider) : Body
        data object ReadMarker : Body
        data class EventBody(val event: RoomEvent) : Body
        data class MessageBody(val message: Message) : Body
        data object Nothing : Body
    }
}

internal object TimelineRows {
    /**
     * Насколько близко два сообщения одного человека, чтобы читаться одной репликой. Пять минут —
     * решение о продукте, а не о протоколе, поэтому названо.
     */
    val RUN_WINDOW: Duration = Duration.ofMinutes(5)

    fun rows(mapped: List<Mapped>): List<TimelineRow> {
        val rows = ArrayList<TimelineRow>(mapped.size)
        var previous: Message? = null
        // Сколько строк покрыто чужой квитанцией — см. [withReadPropagated]. Считается здесь, по
        // элементам SDK: квитанция часто стоит на событии, которое не станет строкой вовсе.
        var readPrefix = 0
        var readPrefixAt: Instant? = null

        for (item in mapped) {
            when (val body = item.body) {
                is Mapped.Body.DayDividerBody -> {
                    rows += TimelineRow.DayDividerRow(body.divider)
                    previous = null
                }
                Mapped.Body.ReadMarker -> {
                    rows += TimelineRow.UnreadDivider
                    previous = null
                }
                is Mapped.Body.EventBody -> {
                    append(body.event, rows)
                    // Заметка поперёк переписки прерывает заход: ниже уже не «тот же человек говорит».
                    previous = null
                }
                is Mapped.Body.MessageBody -> {
                    var message = body.message
                    val above = previous
                    if (above != null && above.senderId == message.senderId &&
                        Duration.between(above.timestamp, message.timestamp) < RUN_WINDOW
                    ) {
                        message = message.copy(isContinuation = true)
                    }
                    rows += TimelineRow.MessageRow(message)
                    previous = message
                }
                Mapped.Body.Nothing -> Unit
            }
            if (item.hasForeignReceipt) {
                readPrefix = rows.size
                readPrefixAt = item.receiptReadAt
            }
        }

        // Сначала полоса, потом пустые дни: дата, единственной строкой под которой была незаслуженная
        // полоса, — дата над ничем.
        return markRunEnds(withoutEmptyDays(withUnreadDividerIfEarned(withReadPropagated(rows, readPrefix, readPrefixAt))))
    }

    /** Отмечает сообщения, под которыми продолжение, — лицо у последнего в заходе. */
    fun markRunEnds(rows: List<TimelineRow>): List<TimelineRow> = rows.mapIndexed { index, row ->
        val message = row.message ?: return@mapIndexed row
        val continued = rows.getOrNull(index + 1)?.message?.isContinuation == true
        if (message.isContinued == continued) row else TimelineRow.MessageRow(message.copy(isContinued = continued))
    }

    /**
     * Полоса «Непрочитанные» — только где под ней есть чужое. SDK ставит маркер после последнего
     * прочитанного, а приложение выбрасывает многое — полоса могла оказаться над вступлением или над
     * своими же словами. «Непрочитанное» над своим — приложение, не знающее, кто ты.
     */
    fun withUnreadDividerIfEarned(rows: List<TimelineRow>): List<TimelineRow> {
        val index = rows.indexOf(TimelineRow.UnreadDivider)
        if (index < 0) return rows
        val earned = rows.drop(index + 1).any { it.message?.isOwn == false }
        val result = rows.filterNot { it == TimelineRow.UnreadDivider }.toMutableList()
        if (earned) result.add(index, TimelineRow.UnreadDivider)
        return result
    }

    /**
     * Свои ранние сообщения — прочитаны, как только прочитано позднее. Квитанция называет одно событие,
     * но она монотонна: прочесть N — прочесть всё до. Отметка «прочитано» — одна, на новейшем
     * прочитанном своём, а не пятнадцать глаз вдоль переписки.
     */
    fun withReadPropagated(rows: List<TimelineRow>, readUpTo: Int = 0, at: Instant? = null): List<TimelineRow> {
        val result = rows.toMutableList()
        var seenRead = false
        var marked = false
        var moment: Instant? = null

        for (index in result.indices.reversed()) {
            var message = result[index].message ?: continue
            if (!message.isOwn) continue

            if (index < readUpTo && moment == null) moment = at

            if (message.delivery == DeliveryStatus.Read) {
                seenRead = true
                moment = message.readAt ?: moment
            } else if (message.delivery == DeliveryStatus.Sent && (seenRead || index < readUpTo)) {
                message = message.copy(delivery = DeliveryStatus.Read)
            }

            if (message.delivery == DeliveryStatus.Read) {
                message = message.copy(readAt = message.readAt ?: moment)
                if (!marked) {
                    message = message.copy(carriesReadMark = true)
                    marked = true
                }
            }
            result[index] = TimelineRow.MessageRow(message)
        }
        return result
    }

    /**
     * Убирает даты, под которыми ничего нет. SDK ставит дату перед каждым событием, а приложение
     * многие из них не показывает — и заголовок оставался объявлять день, всё содержимое которого
     * выброшено. Дата живёт, если *сразу* под ней строка, а не другая дата и не конец списка.
     */
    fun withoutEmptyDays(rows: List<TimelineRow>): List<TimelineRow> = rows.filterIndexed { index, row ->
        if (row !is TimelineRow.DayDividerRow) return@filterIndexed true
        val next = rows.getOrNull(index + 1) ?: return@filterIndexed false
        next !is TimelineRow.DayDividerRow
    }

    /**
     * Добавляет служебную строку, вливая её в предыдущую, где они говорят одно. Слитая строка
     * сохраняет ID *первой*: второй вошедший дописывается в строку, уже стоящую на экране.
     */
    private fun append(event: RoomEvent, rows: MutableList<TimelineRow>) {
        val previous = (rows.lastOrNull() as? TimelineRow.EventRow)?.event
        val merged = previous?.kind?.merged(event.kind)
        if (previous != null && merged != null) {
            rows[rows.lastIndex] = TimelineRow.EventRow(RoomEvent(previous.id, merged, event.timestamp))
            return
        }
        rows += TimelineRow.EventRow(event)
    }
}
