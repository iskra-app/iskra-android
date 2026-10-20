package wtf.iskra.features

/**
 * Тот, у кого граф процесса, — `Application` в `:app`. Receiver'ам и сервисам `:features` граф
 * больше взять неоткуда: их создаёт система, а не конструктор.
 */
interface AppGraphOwner {
    val graph: AppGraph
}
