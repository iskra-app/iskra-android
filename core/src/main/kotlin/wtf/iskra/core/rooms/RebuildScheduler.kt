package wtf.iskra.core.rooms

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Склеивает пачку диффов в одну пересборку — и никогда не даёт пачке пересборку задушить. Порт
 * `RoomListStore.scheduleRebuild` (docs/PROMPT.md, 4.1: «склеивает с задержкой 50 мс»).
 *
 * На iOS это когда-то было «отменить и начать заново»: каждый дифф убивал идущую пересборку и
 * заводил таймер снова. Это прекрасно склеивает, когда диффы кончаются, и голодает, когда нет:
 * пересборка — поход в Rust на комнату, на сотнях чатов это заметное время, а во время начальной
 * синхронизации диффы идут всё это время. Пересборка не доходила до конца, список не появлялся, и
 * человек смотрел на «Пока нет чатов» со всеми разговорами уже в сторе.
 *
 * Поэтому — по заднему фронту: одна пересборка доходит до конца, а пришедшее во время неё поднимает
 * флаг, а не нож. Закончив, она смотрит на флаг и идёт ещё круг. Сто диффов за одну пересборку —
 * одна пересборка ещё, а не сто, но пересборка всегда *заканчивается*.
 *
 * `only` сужает: дифф называет комнаты, о которых он, — их и спрашивают снова, остальные строки
 * остаются какими были. Без аргумента (переименование, заглушение, конец догонялки) спрашивают всех.
 *
 * Всё вызывается на одном потоке — потоке стора; синхронизации здесь поэтому нет.
 */
internal class RebuildScheduler(
    private val scope: CoroutineScope,
    private val debounce: Duration,
    private val rebuild: suspend (changed: Set<String>?) -> Unit,
) {
    private var job: Job? = null
    private var needsRebuild = false
    private var needsFull = true
    private val changed = mutableSetOf<String>()

    /**
     * Какой прогон. [cancel] его увеличивает, а хвост задачи сверяет: иначе старая задача, всё ещё
     * сидящая в `rebuild` после отмены, проснувшись, обнулила бы регистрацию новой — и две
     * пересборки пошли бы параллельно, а победила бы медленная, со старым ответом.
     */
    private var generation = 0

    fun schedule(only: Set<String>? = null) {
        if (only != null) changed += only else needsFull = true
        needsRebuild = true
        if (job != null) return

        val mine = generation
        job = scope.launch {
            try {
                // Первой пачке дают улечься до всякой асинхронной работы.
                delay(debounce)
                while (needsRebuild && isActive && mine == generation) {
                    needsRebuild = false
                    val full = needsFull
                    val asking = changed.toSet()
                    needsFull = false
                    changed.clear()
                    rebuild(if (full) null else asking)
                }
            } finally {
                // И когда пересборка бросила: иначе `job` остался бы занят навсегда, и список
                // больше не пересобрался бы ни разу.
                if (mine == generation) job = null
            }
        }
    }

    fun cancel() {
        generation += 1
        job?.cancel()
        job = null
        needsRebuild = false
        needsFull = true
        changed.clear()
    }
}
