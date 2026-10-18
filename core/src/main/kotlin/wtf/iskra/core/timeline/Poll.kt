package wtf.iskra.core.timeline

/**
 * Опрос, как комната его насчитала. Порт `Poll.swift`.
 *
 * Считает SDK: собирает все `m.poll.response` под `m.poll.start`, оставляет от каждого последний
 * ответ, выбрасывает пришедшее после конца — и отдаёт, кто что выбрал. Здесь этот подсчёт
 * перевёрнут в то, что рисует пузырь: у ответа — сколько и есть ли среди них ты.
 *
 * Голоса считаются по *людям*, не по галочкам. В опросе, где можно выбрать два, трое могут поставить
 * пять галочек, и «60 %» должно значить «трое из пяти человек», а не «три из пяти галочек» — иначе
 * полосы опроса с несколькими ответами в сумме больше целого.
 */
data class Poll(
    val question: String,
    val answers: List<Answer>,
    /** Сколько ответов можно выбрать одному. Один — обычный опрос. */
    val maxSelections: Int,
    /**
     * Можно ли видеть, как идёт, до конца. Закрытый показывает только вопрос, пока его не завершат, —
     * так попросил отправитель, и клиент, рисующий полосы всё равно, решал бы за него.
     */
    val isDisclosed: Boolean,
    val isEnded: Boolean,
    /** Сколько разных людей ответило. */
    val voters: Int,
) {
    data class Answer(
        val id: String,
        val text: String,
        val votes: Int,
        /** Один из голосов — свой. */
        val isChosen: Boolean,
        /** Больше всех голосов, когда опрос окончен. Таких может быть несколько. */
        val isWinner: Boolean,
    )

    val isMultipleChoice: Boolean get() = maxSelections > 1

    val hasVoted: Boolean get() = answers.any { it.isChosen }

    /** Полосы — когда опрос окончен или, в открытом, когда ответил сам. До того они только подталкивали бы. */
    val showsResults: Boolean get() = isEnded || (isDisclosed && hasVoted)

    /** Доля проголосовавших людей, выбравших этот ответ, 0…1. */
    fun share(of: Answer): Double = if (voters == 0) 0.0 else of.votes.toDouble() / voters

    /**
     * Каким станет свой выбор после нажатия на ответ. Один ответ — меняется; несколько — галочка
     * ставится или снимается, и сверх `maxSelections` не ставится, а не роняет тихо какую-то из
     * прежних. Null — нажатие ничего не меняет.
     */
    fun choiceAfterTapping(answerId: String): List<String>? {
        if (isEnded || answers.none { it.id == answerId }) return null
        val chosen = answers.filter { it.isChosen }.map { it.id }
        if (!isMultipleChoice) return if (chosen == listOf(answerId)) null else listOf(answerId)
        if (answerId in chosen) return chosen - answerId
        if (chosen.size >= maxSelections) return null
        return chosen + answerId
    }

    companion object {
        /** Из подсчёта SDK. `votes` — ID ответа → люди, которые его выбрали. */
        fun of(
            question: String,
            isDisclosed: Boolean,
            maxSelections: Long,
            answers: List<Pair<String, String>>,
            votes: Map<String, List<String>>,
            isEnded: Boolean,
            ownUserId: String,
        ): Poll {
            val known = answers.map { it.first }.toSet()
            val counted = votes.filterKeys { it in known }
            val voters = counted.values.flatten().toSet().size
            val most = if (isEnded) counted.values.maxOfOrNull { it.size } ?: 0 else 0
            return Poll(
                question = question,
                answers = answers.map { (id, text) ->
                    val people = counted[id].orEmpty()
                    Answer(id, text, people.size, ownUserId in people, most > 0 && people.size == most)
                },
                maxSelections = maxSelections.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                isDisclosed = isDisclosed,
                isEnded = isEnded,
                voters = voters,
            )
        }
    }
}
