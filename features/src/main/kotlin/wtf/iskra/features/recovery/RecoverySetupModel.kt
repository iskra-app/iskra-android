package wtf.iskra.features.recovery

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import wtf.iskra.core.session.AccountActionFailure
import wtf.iskra.core.session.HistoryProtection
import wtf.iskra.core.session.RecoveryKey
import wtf.iskra.core.session.RecoveryStore
import wtf.iskra.core.session.StartOverApproval
import wtf.iskra.core.session.UserSession
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.TimeoutError
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/**
 * Что делает устройство с историей после входа. Порт `RecoverySetupModel` (iOS, `docs/matrix-device-recovery-flow.md`):
 *
 * - **новый аккаунт** — резервная копия включается сама, человеку показывают код один раз;
 * - **устройство, которое не может прочесть историю** — два равных пути: код или другое устройство;
 * - **код потерян** — другое устройство и новый код, или честный «чистый лист».
 *
 * Ни слова «верификация», «cross-signing», «secret storage»: только «код восстановления» и «резервная копия».
 */
class RecoverySetupModel(val session: UserSession) {
    enum class Step { PREPARING, CONFIRM_NEW, CREATING, CODE, CHOOSE, ENTERING, LOST, STARTING_OVER, PASSWORD, APPROVING, DONE }

    private val mutableStep = MutableStateFlow(Step.PREPARING)
    val step: StateFlow<Step> = mutableStep.asStateFlow()

    /** Код, пока он на экране. Только здесь, и только пока его показывают. */
    private val mutableCode = MutableStateFlow<RecoveryKey?>(null)
    val code: StateFlow<RecoveryKey?> = mutableCode.asStateFlow()

    private val mutableFailure = MutableStateFlow<AccountActionFailure?>(null)
    val failure: StateFlow<AccountActionFailure?> = mutableFailure.asStateFlow()

    private val mutableHasOtherDevices = MutableStateFlow(false)
    val hasOtherDevices: StateFlow<Boolean> = mutableHasOtherDevices.asStateFlow()

    /** Куда ведёт страница сервера при «чистом листе» — экран откроет её во вкладке браузера. */
    private val mutableApprovalUrl = MutableStateFlow<String?>(null)
    val approvalUrl: StateFlow<String?> = mutableApprovalUrl.asStateFlow()

    private val recovery: RecoveryStore get() = session.recovery
    val verification get() = session.verification

    private enum class AfterConfirming { FINISH, MINT_NEW_CODE }

    private var afterConfirming = AfterConfirming.FINISH

    fun clearFailure() {
        mutableFailure.value = null
    }

    suspend fun begin() {
        // Ограничено: иначе человека держал бы перед спиннером сервер, который перестал отвечать.
        // Пустить в приложение по таймауту — безопасный исход: баннер и настройки остаются.
        try {
            withTimeLimit(SETUP_LIMIT) { recovery.waitForSetup() }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutError) throw error
            Log.error(Log.Category.SESSION, "шифрование аккаунта не улеглось — пускаем без настройки")
            mutableStep.value = Step.DONE
            return
        }
        mutableStep.value = route(recovery.protection.value)
        if (mutableStep.value == Step.CREATING) create()
    }

    // Поток 1 — новый аккаунт.

    private suspend fun create() {
        mutableStep.value = Step.CREATING
        val minted = recovery.protectHistory()
        if (minted == null) {
            // Не вышло, и человеку тут делать нечего: пускаем, баннер и настройки попросят снова.
            mutableFailure.value = recovery.failure
            mutableStep.value = Step.DONE
            return
        }
        mutableCode.value = minted
        mutableStep.value = Step.CODE
    }

    fun savedCode() {
        mutableCode.value = null
        mutableStep.value = Step.DONE
    }

    // Поток 2 — устройство, которое не может прочесть историю.

    fun chooseCode() {
        mutableStep.value = Step.ENTERING
    }

    fun backToChoice() {
        mutableStep.value = Step.CHOOSE
    }

    suspend fun unlock(code: String): Boolean {
        if (!recovery.restore(code)) {
            mutableFailure.value = recovery.failure
            return false
        }
        mutableStep.value = Step.DONE
        return true
    }

    suspend fun confirmFromAnotherDevice() {
        afterConfirming = AfterConfirming.FINISH
        verification.start()
    }

    /** Подтверждение прошло. После потерянного кода новый — *предлагается*, а не делается молча. */
    fun confirmed() {
        mutableStep.value = when (afterConfirming) {
            AfterConfirming.FINISH -> Step.DONE
            // Старый код не потерян, а *засунут куда-то*: сравнение отдало устройству секреты, но
            // хранилище не тронуло, и старый код всё ещё его открывает. Новый код убил бы его.
            AfterConfirming.MINT_NEW_CODE -> Step.CONFIRM_NEW
        }
    }

    suspend fun confirmNewCode() {
        val minted = recovery.makeNewKey()
        if (minted == null) {
            mutableFailure.value = recovery.failure
            mutableStep.value = Step.DONE
            return
        }
        mutableCode.value = minted
        mutableStep.value = Step.CODE
    }

    fun keepOldCode() {
        mutableStep.value = Step.DONE
    }

    // Поток 3 — кода нет.

    suspend fun lostTheCode() {
        mutableHasOtherDevices.value = recovery.hasOtherDevices()
        mutableStep.value = Step.LOST
    }

    suspend fun recoverWithAnotherDevice() {
        afterConfirming = AfterConfirming.MINT_NEW_CODE
        verification.start()
    }

    fun noAccessToOtherDevice() {
        mutableHasOtherDevices.value = false
    }

    suspend fun startOver() {
        mutableStep.value = Step.STARTING_OVER
        try {
            when (val approval = recovery.beginStartOver()) {
                StartOverApproval.None -> finishStartOver()
                is StartOverApproval.Browser -> {
                    // А потом *спросить*: закрыть браузер, одобрив, и закрыть, передумав, — одно событие.
                    mutableApprovalUrl.value = approval.url
                    mutableStep.value = Step.APPROVING
                }
                StartOverApproval.Password -> mutableStep.value = Step.PASSWORD
            }
        } catch (_: RecoveryStore.StartOverFailed) {
            mutableFailure.value = AccountActionFailure.START_OVER
            mutableStep.value = Step.LOST
        }
    }

    suspend fun approved() {
        mutableApprovalUrl.value = null
        mutableStep.value = Step.STARTING_OVER
        finishStartOver()
    }

    suspend fun abandonStartOver() {
        mutableApprovalUrl.value = null
        recovery.cancelStartOver()
        mutableStep.value = Step.LOST
    }

    suspend fun confirmWithPassword(password: String) {
        mutableStep.value = Step.STARTING_OVER
        finishStartOver(password)
    }

    private suspend fun finishStartOver(password: String? = null) {
        try {
            recovery.finishStartOver(password, session.userId)
        } catch (_: RecoveryStore.StartOverFailed) {
            mutableFailure.value = AccountActionFailure.START_OVER
            mutableStep.value = Step.LOST
            return
        }
        // Личность новая, и за ней нет копии. Сделать её сейчас — разница между «начать с чистого
        // листа» и «оказаться ровно здесь же на следующем устройстве».
        create()
    }

    companion object {
        private val SETUP_LIMIT = 30.seconds

        /** `Unknown` — SDK не знает, и с «не знаю» не делают ничего: пустить в приложение обратимо. */
        fun route(protection: HistoryProtection): Step = when (protection) {
            HistoryProtection.OFF -> Step.CREATING
            HistoryProtection.KEY_NEEDED -> Step.CHOOSE
            HistoryProtection.ON, HistoryProtection.UNKNOWN -> Step.DONE
        }
    }
}
