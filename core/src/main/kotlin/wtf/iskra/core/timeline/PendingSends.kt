package wtf.iskra.core.timeline

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Сообщения, на которых нажали «отправить», а SDK их ещё не вернул эхом. Порт `PendingSends.swift`.
 *
 * Эхо приходит почти сразу: сообщение ложится в зашифрованную очередь и возвращается элементом ленты
 * — это и ставит пузырь с часами. «Почти» и есть беда: несколько сотен миллисекунд человек смотрит на
 * уже пустой композер и на переписку, где ничего не изменилось, — приложение будто проглотило
 * написанное. Поэтому слова встают на экран *отсюда*, а отправка идёт за ними; каждый такой пузырь
 * живёт ровно до прихода настоящего.
 *
 * Узнать, когда тот пришёл, — трудная часть: ID транзакции эха через FFI не виден, сверять можно
 * только по тому, что сообщение *говорит* (см. [pruneEchoed] и записанный там перекос).
 */
class PendingSends {
    /** Одно сообщение в пути. */
    data class Pending(
        /** Не похож ни на ID события, ни на ID транзакции: хендла отправки по нему не найдёт никто. */
        val id: String,
        /** Как набрано, без пробелов по краям, — то, что рисует пузырь. */
        val text: String,
        /**
         * Тело, ушедшее в SDK, — с ним и сверяется эхо. Отличается от [text] упоминаниями: `@Имя`
         * уходит ссылкой `matrix.to` в Markdown, и тело события — уже она (в iOS сверка по набранному,
         * и сообщение с упоминанием ждало бы эха, которое на него не похоже, все [LIFETIME]).
         */
        val body: String,
        /** Сообщение, на которое отвечают. ID сообщения, как везде выше SDK. */
        val replyingTo: String?,
        val sentAt: Instant,
        /**
         * Свои сообщения, уже бывшие на экране при нажатии. Ни одно не может быть эхом этого: они
         * были раньше, чем его написали. Без этого второе «ок» в пределах двух минут от первого
         * снималось о строку *первого*.
         */
        val predates: Set<String>,
    )

    var all: List<Pending> = emptyList()
        private set

    val isEmpty: Boolean get() = all.isEmpty()

    /** Ставит сообщение на экран. Возвращает его ID, чтобы отправка могла снять его, если SDK откажет сразу. */
    fun add(
        text: String,
        replyingTo: String?,
        body: String = text,
        at: Instant = Instant.now(),
        alreadyShown: Set<String> = emptySet(),
    ): String {
        val pending = Pending("pending-${UUID.randomUUID()}", text, body, replyingTo, at, alreadyShown)
        all = all + pending
        return pending.id
    }

    fun remove(id: String) {
        all = all.filterNot { it.id == id }
    }

    fun removeAll() {
        all = emptyList()
    }

    /**
     * Снимает те, что SDK уже вернул, — настоящий пузырь встаёт на место нашего, а не рядом.
     *
     * По словам — больше не по чему. Два одинаковых своих различаются *счётом*: три эха «ок»
     * снимают три ждущих «ок», не больше; и только эхо не старше [ECHO_WINDOW] до первой отправки.
     *
     * Где догадке суждено ошибиться, она ошибается в безвредную сторону. Снять слишком рано — потерять
     * те самые сотни миллисекунд, и только: эхо, принятое за наше, на экране. Снять слишком поздно —
     * одна фраза в переписке дважды, а это читающий заметит. Поэтому окно щедрое, а ничья — в пользу
     * забыть.
     *
     * Возвращает, какое эхо сменило какой наш пузырь: ID настоящего сообщения → ID, под которым
     * пузырь уже стоял на экране. По этому экран оставляет *ту же* строку (см. `TimelineState.viewKey`).
     */
    fun pruneEchoed(rows: List<TimelineRow>): Map<String, String> {
        val earliest = all.minOfOrNull { it.sentAt } ?: return emptyMap()
        val since = earliest - ECHO_WINDOW

        // По порядку и по ID, а не только счётом: второе эхо «ок» — второму нашему «ок».
        val echoes = mutableMapOf<String, MutableList<String>>()
        for (row in rows) {
            val message = row.message ?: continue
            val kind = message.kind as? MessageKind.Text ?: continue
            if (!message.isOwn || message.timestamp < since) continue
            echoes.getOrPut(kind.body) { mutableListOf() }.add(message.id)
        }

        val taken = mutableMapOf<String, String>()
        val used = mutableSetOf<String>()
        all = all.filterNot { pending ->
            // Первое эхо этих слов, ещё никому не отданное и не старше этой отправки.
            val echo = echoes[pending.body]?.firstOrNull { it !in used && it !in pending.predates } ?: return@filterNot false
            used += echo
            taken[echo] = pending.id
            true
        }
        return taken
    }

    companion object {
        /**
         * Сколько наш пузырь ждёт эха, прежде чем уйти. Не сбой и не сообщается как сбой: сообщение
         * в зашифрованной очереди SDK, а она и ручается, что оно уйдёт, — и встанет обычным пузырём,
         * как лента его догонит. Достаточно, чтобы не оборвать ни одну обычную отправку; мало, чтобы
         * пузырь, который ничто не сменило, не стоял в переписке навсегда.
         */
        val LIFETIME: Duration = Duration.ofSeconds(15)

        /**
         * Насколько глубоко в переписке может прятаться наше эхо. Часы, приостановленная очередь,
         * написанное без сети: время эха — SDK, не наше, и оно не обязано быть позже нажатия.
         */
        private val ECHO_WINDOW: Duration = Duration.ofSeconds(120)
    }
}
