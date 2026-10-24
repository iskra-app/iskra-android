package wtf.iskra.core.timeline

import kotlin.math.abs

/**
 * Фото и видео, отправленные вместе, — одна картинка из нескольких, альбом Telegram. Порт `Album.swift`.
 *
 * В Matrix альбома нет: каждое фото — своё событие, а предложение, которое связало бы их (MSC4274),
 * — черновик, которого никто не шлёт. Поэтому альбом решается здесь, на принимающей стороне, из того,
 * что события говорят: один человек, картинка за картинкой без ничего между ними, *почти в один
 * момент*. Шесть фото, выбранных и отправленных одним нажатием, уходят из очереди одно за другим и
 * приходят с разницей в секунды; те же шесть, отправленные за обедом, — шесть сообщений.
 *
 * Пауза меряется между *соседями*, а не от первого до последнего: очередь грузит по одному файлу, и
 * на медленной сети десять фото приходят минуту, хотя ни два соседних не дальше нескольких секунд.
 *
 * Строка альбома — его **первое** сообщение: то, на что ложатся реакция, ответ, закрепление. Первое,
 * а не последнее — чтобы id строки не менялся, пока догружается остальное.
 *
 * Альбом ломает то, что его ломает: сказанное между, дата, полоса непрочитанного, стикер или кружок,
 * ответ или подпись где-либо, кроме первого.
 */
object Album {
    /** Самая долгая пауза между двумя картинками одного альбома, мс. */
    const val GAP_MS = 15_000L

    /** Предел Telegram и системного выбора: десять в альбоме. */
    const val LIMIT = 10

    /** Строки, где каждый альбом свёрнут в своё первое сообщение. */
    fun grouping(rows: List<TimelineRow>): List<TimelineRow> {
        val result = ArrayList<TimelineRow>(rows.size)
        val run = ArrayList<Message>()

        fun flush() {
            val first = run.firstOrNull() ?: return
            result.add(TimelineRow.MessageRow(if (run.size == 1) first else folded(run)))
            run.clear()
        }

        for (row in rows) {
            val message = (row as? TimelineRow.MessageRow)?.message
            if (message == null || !isPicture(message)) {
                flush()
                result.add(row)
                continue
            }
            val last = run.lastOrNull()
            if (last != null && !belongs(message, last, run.size)) flush()
            run.add(message)
        }
        flush()
        return result
    }

    /** Фото или обычное видео — то, из чего альбом и состоит. Кружок и стикер — сказанное, не набор. */
    fun isPicture(message: Message): Boolean =
        !message.isDeleted && (message.kind is MessageKind.Image || message.kind is MessageKind.Video)

    private fun belongs(message: Message, last: Message, size: Int): Boolean =
        size < LIMIT &&
            message.senderId == last.senderId &&
            message.replyTo == null &&
            message.forwardedFrom == null &&
            // Подпись открывает альбом; подпись дальше — начало следующего.
            message.kind.caption == null &&
            abs(message.timestamp.toEpochMilli() - last.timestamp.toEpochMilli()) <= GAP_MS

    /**
     * Первое сообщение, несущее весь альбом, — с доставкой не дальше самой отстающей картинки и с
     * отметкой «прочитано», если она была у любой.
     */
    private fun folded(run: List<Message>): Message {
        val lead = run[0]
        return lead.copy(
            album = run.toList(),
            delivery = run.mapNotNull { it.delivery }.minByOrNull(::rank) ?: lead.delivery,
            carriesReadMark = run.any { it.carriesReadMark },
            uploadProgress = run.mapNotNull { it.uploadProgress }.takeIf { it.isNotEmpty() }?.average(),
            isContinued = run.last().isContinued,
        )
    }

    /** Меньше — отстаёт. Сбой отстаёт от всего: одно неушедшее фото — неушедший альбом. */
    private fun rank(status: DeliveryStatus): Int = when (status) {
        is DeliveryStatus.Failed -> 0
        DeliveryStatus.Sending -> 1
        DeliveryStatus.Sent -> 2
        DeliveryStatus.Read -> 3
    }
}

/** Сообщение с этим id, где бы оно ни было, — своей строкой или картинкой внутри альбома. */
fun List<TimelineRow>.message(withId: String): Message? {
    for (row in this) {
        val message = (row as? TimelineRow.MessageRow)?.message ?: continue
        if (message.id == withId) return message
        message.album?.firstOrNull { it.id == withId }?.let { return it }
    }
    return null
}
