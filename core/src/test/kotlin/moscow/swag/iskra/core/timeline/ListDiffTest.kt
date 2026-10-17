package moscow.swag.iskra.core.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Арифметика, которая держит переписку в ногу с SDK. Порт `ListDiffTests`.
 *
 * Обобщённая по элементу, она принимает строки — и тогда это просто список и несколько индексов.
 */
class ListDiffTest {
    private fun list() = mutableListOf("a", "b", "c")

    private fun applied(diff: ListDiff<String>): List<String> {
        val items = list()
        assertEquals(DiffOutcome.Applied, items.applyDiffs(listOf(diff)))
        return items
    }

    @Test
    fun theOrdinaryCasesPutThingsWhereTheyBelong() {
        assertEquals(listOf("a", "b", "c", "d", "e"), applied(ListDiff.Append(listOf("d", "e"))))
        assertEquals(emptyList(), applied(ListDiff.Clear))
        assertEquals(listOf("z", "a", "b", "c"), applied(ListDiff.PushFront("z")))
        assertEquals(listOf("a", "b", "c", "z"), applied(ListDiff.PushBack("z")))
        assertEquals(listOf("b", "c"), applied(ListDiff.PopFront))
        assertEquals(listOf("a", "b"), applied(ListDiff.PopBack))
        assertEquals(listOf("a", "z", "b", "c"), applied(ListDiff.Insert(1, "z")))
        assertEquals(listOf("a", "z", "c"), applied(ListDiff.Set(1, "z")))
        assertEquals(listOf("a", "c"), applied(ListDiff.Remove(1)))
        assertEquals(listOf("a"), applied(ListDiff.Truncate(1)))
        assertEquals(listOf("x", "y"), applied(ListDiff.Reset(listOf("x", "y"))))
    }

    @Test
    fun aBatchIsAppliedInOrderNotAllAtOnce() {
        val items = list()
        assertEquals(
            DiffOutcome.Applied,
            items.applyDiffs(listOf(ListDiff.PopFront, ListDiff.PushBack("d"), ListDiff.Set(0, "B"))),
        )
        assertEquals(listOf("B", "c", "d"), items)
    }

    /** Вставка *в конец* — обычная вставка, а не ошибка на единицу. Единственное место, где граница `<=`. */
    @Test
    fun insertingOnePastTheLastItemIsInsertingAtTheEnd() {
        assertEquals(listOf("a", "b", "c", "d"), applied(ListDiff.Insert(3, "d")))
    }

    /**
     * Вся причина, по которой этот код стоит теста. Индекс, которого у нас нет, значит, что SDK
     * говорит о чужом списке, — и подрезать его значит оставить переписку навсегда перемешанной.
     */
    @Test
    fun anIndexWeDoNotHaveIsADesyncNotSomethingToStepOver() {
        val impossible = listOf<Pair<String, ListDiff<String>>>(
            "insert past the end" to ListDiff.Insert(4, "z"),
            "insert at a negative index" to ListDiff.Insert(-1, "z"),
            "set past the end" to ListDiff.Set(3, "z"),
            "set on a negative index" to ListDiff.Set(-1, "z"),
            "remove past the end" to ListDiff.Remove(3),
            "remove at a negative index" to ListDiff.Remove(-1),
            "truncate to more than we hold" to ListDiff.Truncate(4),
            "truncate to a negative length" to ListDiff.Truncate(-1),
        )
        for ((what, diff) in impossible) {
            val items = list()
            assertIs<DiffOutcome.Desynced>(items.applyDiffs(listOf(diff)), "$what should have desynced")
            assertEquals(list(), items, "$what should have changed nothing")
        }
    }

    /** И пачка на этом останавливается: остальные индексы мерены по списку, которого у нас уже нет. */
    @Test
    fun nothingAfterADesyncIsApplied() {
        val items = list()
        assertIs<DiffOutcome.Desynced>(items.applyDiffs(listOf(ListDiff.PopFront, ListDiff.Remove(9), ListDiff.Clear)))
        // Снятие легло; очистка, шедшая после плохого индекса, — нет.
        assertEquals(listOf("b", "c"), items)
    }

    /** Без индекса и без утверждения о содержимом — не рассинхрон. Асимметрия намеренная. */
    @Test
    fun poppingAnEmptyListIsNotAnError() {
        val items = mutableListOf<String>()
        assertEquals(DiffOutcome.Applied, items.applyDiffs(listOf(ListDiff.PopFront, ListDiff.PopBack)))
        assertTrue(items.isEmpty())
    }

    @Test
    fun truncatingToNothingEmptiesAndToItsOwnLengthChangesNothing() {
        assertEquals(emptyList(), applied(ListDiff.Truncate(0)))
        assertEquals(list(), applied(ListDiff.Truncate(3)))
    }

    @Test
    fun mappingKeepsTheShapeOfTheDiff() {
        assertEquals(ListDiff.Insert(2, 1), ListDiff.Insert(2, "z").map { it.length })
        assertEquals(ListDiff.Remove(4), ListDiff.Remove(4).map { _: Nothing -> 0 })
    }
}
