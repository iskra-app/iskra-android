package wtf.iskra.core.timeline

import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.TimelineDiff

/**
 * Одно позиционное изменение упорядоченного списка. Порт `ListDiff.swift`.
 *
 * Это `TimelineDiff` (и `RoomListEntriesUpdate`) SDK без SDK — и смысл в том, чтобы результат можно
 * было *проверить тестом*. `TimelineItem` и `Room` — хендлы Rust-объектов, в тесте их не создать, и
 * код, говорящий на их языке, никогда не получил бы список, к которому применять диффы. А применяется
 * не формальность: дюжина позиционных операций, часть которых может указать на элемент, которого в
 * списке нет, и решение, что тогда делать, — это разница между перепиской правильной и тихо,
 * навсегда неправильной.
 *
 * Поэтому арифметика живёт здесь, обобщённая по элементу, с тестом на строках; оба стора, которые
 * получают настоящие диффы, переводят их сюда и получают одну и ту же арифметику (docs/PROMPT.md, 4.1).
 */
sealed interface ListDiff<out T> {
    data class Append<T>(val values: List<T>) : ListDiff<T>
    data object Clear : ListDiff<Nothing>
    data class PushFront<T>(val value: T) : ListDiff<T>
    data class PushBack<T>(val value: T) : ListDiff<T>
    data object PopFront : ListDiff<Nothing>
    data object PopBack : ListDiff<Nothing>
    data class Insert<T>(val index: Int, val value: T) : ListDiff<T>
    data class Set<T>(val index: Int, val value: T) : ListDiff<T>
    data class Remove(val index: Int) : ListDiff<Nothing>
    data class Truncate(val length: Int) : ListDiff<Nothing>
    data class Reset<T>(val values: List<T>) : ListDiff<T>

    /** Тот же дифф с другим элементом — для `Room` → запись с уже прочитанным ID. */
    fun <R> map(transform: (T) -> R): ListDiff<R> = when (this) {
        is Append -> Append(values.map(transform))
        Clear -> Clear
        is PushFront -> PushFront(transform(value))
        is PushBack -> PushBack(transform(value))
        PopFront -> PopFront
        PopBack -> PopBack
        is Insert -> Insert(index, transform(value))
        is Set -> Set(index, transform(value))
        is Remove -> this
        is Truncate -> this
        is Reset -> Reset(values.map(transform))
    }
}

/** Согласен ли ещё список с SDK о том, что в нём лежит. */
sealed interface DiffOutcome {
    data object Applied : DiffOutcome

    /**
     * SDK указал на элемент, которого у нас нет, — и перешагнуть это нельзя. Это SDK говорит о
     * списке, который не наш: наша копия уже разошлась с его, и каждый позиционный дифф после этого
     * ляжет не туда. Строка — какой именно, для журнала.
     */
    data class Desynced(val what: String) : DiffOutcome
}

/**
 * Применяет пачку диффов по порядку и останавливается на первом, который не подходит.
 *
 * Три проверки границ — вся причина, по которой это отдельная именованная вещь. Раньше (на iOS) они
 * подрезали и проглатывали: `insert` клал в конец вместо названного места, `set` и `remove` не
 * делали ничего — и массив оставался тихо неправильным, без падения, без записи в журнале. Так в
 * ленте прятался баг перестановки: гонка применяла две пачки не в том порядке, проверки глотали
 * невозможные индексы, и переписка оставалась перемешанной без следа.
 *
 * Невозможный индекс поэтому *значит* что-то: список говорит, что ему больше нельзя верить, и ответ
 * на это — прочитать его заново.
 *
 * `PopFront` и `PopBack` на пустом списке в эту категорию **не** входят, и асимметрия намеренная: у
 * них нет индекса и они ничего не утверждают о содержимом; SDK вправе снять с пустого списка и
 * ничего этим не сказать.
 */
fun <T> MutableList<T>.applyDiffs(diffs: List<ListDiff<T>>): DiffOutcome {
    for (diff in diffs) {
        when (diff) {
            is ListDiff.Append -> addAll(diff.values)
            ListDiff.Clear -> clear()
            is ListDiff.PushFront -> add(0, diff.value)
            is ListDiff.PushBack -> add(diff.value)
            ListDiff.PopFront -> if (isNotEmpty()) removeAt(0)
            ListDiff.PopBack -> if (isNotEmpty()) removeAt(lastIndex)
            is ListDiff.Insert -> {
                // `<=`, а не `<`: вставка *в конец* — законная вставка, и единственное место, где
                // граница отличается от проверок ниже.
                if (diff.index !in 0..size) return DiffOutcome.Desynced("insert at ${diff.index} of $size")
                add(diff.index, diff.value)
            }
            is ListDiff.Set -> {
                if (diff.index !in indices) return DiffOutcome.Desynced("set at ${diff.index} of $size")
                this[diff.index] = diff.value
            }
            is ListDiff.Remove -> {
                if (diff.index !in indices) return DiffOutcome.Desynced("remove at ${diff.index} of $size")
                removeAt(diff.index)
            }
            is ListDiff.Truncate -> {
                if (diff.length !in 0..size) return DiffOutcome.Desynced("truncate to ${diff.length} of $size")
                subList(diff.length, size).clear()
            }
            is ListDiff.Reset -> {
                clear()
                addAll(diff.values)
            }
        }
    }
    return DiffOutcome.Applied
}

/**
 * Индексы SDK — беззнаковые. Число больше `Int.MAX_VALUE` не может указывать ни на что в списке,
 * который помещается в память, — оно становится -1, и проверка границ называет его рассинхроном,
 * а не тихо оборачивается в отрицательное.
 */
private fun UInt.asIndex(): Int = if (this > Int.MAX_VALUE.toUInt()) -1 else toInt()

/** Тот же дифф ленты, сказанный без SDK. Чисто механически: каждый случай — в одноимённый. */
fun TimelineDiff.asListDiff(): ListDiff<org.matrix.rustcomponents.sdk.TimelineItem> = when (this) {
    is TimelineDiff.Append -> ListDiff.Append(values)
    is TimelineDiff.Clear -> ListDiff.Clear
    is TimelineDiff.PushFront -> ListDiff.PushFront(value)
    is TimelineDiff.PushBack -> ListDiff.PushBack(value)
    is TimelineDiff.PopFront -> ListDiff.PopFront
    is TimelineDiff.PopBack -> ListDiff.PopBack
    is TimelineDiff.Insert -> ListDiff.Insert(index.asIndex(), value)
    is TimelineDiff.Set -> ListDiff.Set(index.asIndex(), value)
    is TimelineDiff.Remove -> ListDiff.Remove(index.asIndex())
    is TimelineDiff.Truncate -> ListDiff.Truncate(length.asIndex())
    is TimelineDiff.Reset -> ListDiff.Reset(values)
}

/** То же для списка комнат. */
fun RoomListEntriesUpdate.asListDiff(): ListDiff<org.matrix.rustcomponents.sdk.Room> = when (this) {
    is RoomListEntriesUpdate.Append -> ListDiff.Append(values)
    is RoomListEntriesUpdate.Clear -> ListDiff.Clear
    is RoomListEntriesUpdate.PushFront -> ListDiff.PushFront(value)
    is RoomListEntriesUpdate.PushBack -> ListDiff.PushBack(value)
    is RoomListEntriesUpdate.PopFront -> ListDiff.PopFront
    is RoomListEntriesUpdate.PopBack -> ListDiff.PopBack
    is RoomListEntriesUpdate.Insert -> ListDiff.Insert(index.asIndex(), value)
    is RoomListEntriesUpdate.Set -> ListDiff.Set(index.asIndex(), value)
    is RoomListEntriesUpdate.Remove -> ListDiff.Remove(index.asIndex())
    is RoomListEntriesUpdate.Truncate -> ListDiff.Truncate(length.asIndex())
    is RoomListEntriesUpdate.Reset -> ListDiff.Reset(values)
}
