package wtf.iskra.core.timeline

/**
 * Опрос, который человек составляет, — порт `PollDraft`. Пустые варианты по дороге отбрасываются; без вопроса и двух непустых вариантов опрос не
 * уходит.
 */
data class PollDraft(
    val question: String = "",
    val answers: List<String> = listOf("", ""),
    val isMultipleChoice: Boolean = false,
    /** «Итоги, когда закончится»: пока опрос идёт, никто не видит, как проголосовали остальные. */
    val isAnonymous: Boolean = false,
) {
    val trimmedAnswers: List<String> get() = answers.map { it.trim() }.filter { it.isNotEmpty() }

    val canSend: Boolean get() = question.isNotBlank() && trimmedAnswers.size >= 2

    companion object {
        const val MAX_ANSWERS = 20
    }
}
