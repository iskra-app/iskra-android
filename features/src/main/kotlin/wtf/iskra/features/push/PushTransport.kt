package wtf.iskra.features.push

import android.content.Context

/**
 * Как пуши доезжают до этого телефона — своё у каждого flavor'а (docs/PROMPT.md, 10.2): FCM у `gms`,
 * UnifiedPush у `foss`. Реализация живёт в `:app` своего flavor'а, потому что только там библиотека
 * транспорта; адрес, который транспорт выдал, приходит в [Push.targetChanged], пуши — в [Push.handle].
 */
interface PushTransport {
    /** Что честно сказать на экране уведомлений: не что выбрано, а что есть. */
    enum class State {
        /** Транспорт есть и выдал адрес — сервер знает, куда слать. */
        READY,

        /** Транспорт есть, но адреса ещё нет (только что включили или дистрибьютор думает). */
        WAITING,

        /** UnifiedPush: на телефоне нет ни одного дистрибьютора, и пушам ехать не через что. */
        NO_DISTRIBUTOR,

        /** Сборка без настройки транспорта: `gms` без `google-services.json`. Пушей нет. */
        UNAVAILABLE,
    }

    /** Приложение-дистрибьютор UnifiedPush (ntfy и т. п.). */
    data class Distributor(val packageName: String, val label: String)

    /** Попросить адрес. После входа и при каждом запуске с аккаунтом; транспорт может ответить не сразу. */
    fun start(context: Context)

    /** Последний аккаунт вышел — адрес больше никому не нужен. */
    fun stop(context: Context)

    fun state(context: Context): State

    /** Дистрибьюторы UnifiedPush на телефоне. У FCM выбора нет — пусто. */
    fun distributors(context: Context): List<Distributor> = emptyList()

    /** Какой дистрибьютор выбран сейчас. */
    fun distributor(context: Context): String? = null

    /** Выбрать дистрибьютор — и сразу попросить у него адрес. */
    fun choose(context: Context, packageName: String) = Unit
}
