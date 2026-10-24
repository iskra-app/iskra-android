package wtf.iskra.core.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PollDraftTest {
    @Test
    fun anEmptyDraftCannotBeSent() {
        assertFalse(PollDraft().canSend)
    }

    @Test
    fun needsAQuestionAndTwoRealAnswers() {
        assertFalse(PollDraft(question = "Когда?", answers = listOf("Завтра", "  ")).canSend)
        assertFalse(PollDraft(question = "  ", answers = listOf("а", "б")).canSend)
        assertTrue(PollDraft(question = "Когда?", answers = listOf("Завтра", "Послезавтра", "")).canSend)
    }

    @Test
    fun blankAnswersAreDroppedAndTheRestTrimmed() {
        assertEquals(listOf("а", "б"), PollDraft(question = "?", answers = listOf(" а ", "", "б ", "  ")).trimmedAnswers)
    }
}
