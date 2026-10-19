package wtf.iskra.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import wtf.iskra.core.R
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.withTimeLimit
import org.matrix.rustcomponents.sdk.AuthData
import org.matrix.rustcomponents.sdk.AuthDataPasswordDetails
import org.matrix.rustcomponents.sdk.CrossSigningResetAuthType
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.Encryption
import org.matrix.rustcomponents.sdk.IdentityResetHandle
import org.matrix.rustcomponents.sdk.RecoveryException
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Прочитаю ли я всё это на новом телефоне — единственный вопрос, на который отвечает экран
 * безопасности, поэтому состояние называется так (docs/PROMPT.md, 12.8), а не «recovery state».
 */
enum class HistoryProtection {
    /** SDK ещё не знает. С «не знаю» не делают ничего. */
    UNKNOWN,
    ON,
    OFF,

    /** Резервная копия есть, а это устройство её открыть не может — нужен код. */
    KEY_NEEDED,
}

/** Что из настроек аккаунта не получилось — словами (`AccountActionFailure`). */
enum class AccountActionFailure(val explanation: Int) {
    PROTECT_HISTORY(R.string.account_protect_failed),
    NEW_KEY(R.string.account_new_key_failed),
    RESTORE(R.string.account_restore_failed),
    STALE_CODE(R.string.account_stale_code_failed),
    START_OVER(R.string.account_start_over_failed),

    /**
     * Резервная копия на сервере уже есть, а секретного хранилища — нет (`BackupExistsOnServer`): копию
     * сделало другое устройство, и кода к ней не существует. «Защитить заново» тут нечего — это
     * устройство должно получить ключ от того, или начать с чистого листа.
     */
    BACKUP_ELSEWHERE(R.string.account_backup_elsewhere_failed),
}

/** Как сервер хочет подтверждения, прежде чем выбросить старую личность. */
sealed interface StartOverApproval {
    /** Выбрасывать нечего — можно сразу делать новый код. */
    data object None : StartOverApproval

    /** Страница сервера (MAS). */
    data class Browser(val url: String) : StartOverApproval

    /** Пароль (UIA). */
    data object Password : StartOverApproval
}

/**
 * Код восстановления — значение, которое отдаётся *вызывающему* и больше нигде не хранится. Показан
 * ровно раз: им владеет экран, который его показывает, и умирает он вместе с экраном. На диск — ни в
 * каком виде: хранилище секретов положило бы его на то самое устройство, чью потерю он переживает.
 */
@JvmInline
value class RecoveryKey(val value: String)

/**
 * Резервная копия переписки и код восстановления. Порт `RecoveryStore` (`Account.swift`): тот же язык —
 * «код восстановления», «резервная копия», — и та же механика за ним (`docs/matrix-device-recovery-flow.md`
 * в iOS-репозитории).
 */
class RecoveryStore internal constructor(
    private val encryption: Encryption,
    private val scope: CoroutineScope,
) {
    private val mutableProtection = MutableStateFlow(HistoryProtection.UNKNOWN)
    val protection: StateFlow<HistoryProtection> = mutableProtection.asStateFlow()

    private val mutableVerified = MutableStateFlow(false)

    /** Это устройство подтверждено аккаунтом. */
    val isDeviceVerified: StateFlow<Boolean> = mutableVerified.asStateFlow()

    private val mutableWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = mutableWorking.asStateFlow()

    /** Последний сбой — для вызывающего, который покажет его словами. */
    var failure: AccountActionFailure? = null
        private set

    private var subscription: TaskHandle? = null
    private var verification: TaskHandle? = null
    private var reset: IdentityResetHandle? = null

    fun load() {
        if (subscription != null) return
        mutableProtection.value = protection(encryption.recoveryState())
        mutableVerified.value = encryption.verificationState() == VerificationState.VERIFIED
        subscription = encryption.recoveryStateListener(object : RecoveryStateListener {
            override fun onUpdate(status: RecoveryState) {
                scope.launch { mutableProtection.value = protection(status) }
            }
        })
        verification = encryption.verificationStateListener(object : VerificationStateListener {
            override fun onUpdate(status: VerificationState) {
                scope.launch { mutableVerified.value = status == VerificationState.VERIFIED }
            }
        })
    }

    fun stop() {
        subscription?.cancel()
        subscription = null
        verification?.cancel()
        verification = null
    }

    /**
     * Включить резервную копию и получить код. `waitForBackupsToUpload = true` — именно true: код,
     * показанный до того, как ключи ушли на сервер, открыл бы пустую копию.
     */
    suspend fun protectHistory(): RecoveryKey? = working({ failureOfProtecting(it) }) {
        val key = withTimeLimit(ENABLE_LIMIT) {
            encryption.enableRecovery(true, null, object : EnableRecoveryProgressListener {
                override fun onUpdate(status: EnableRecoveryProgress) = Unit
            })
        }
        mutableProtection.value = HistoryProtection.ON
        RecoveryKey(key)
    }

    /** Новый код взамен старого — старый перестаёт работать сразу. */
    suspend fun makeNewKey(): RecoveryKey? = working(AccountActionFailure.NEW_KEY) {
        val key = withTimeLimit(SDK_LIMIT) { encryption.resetRecoveryKey() }
        mutableProtection.value = HistoryProtection.ON
        RecoveryKey(key)
    }

    /** Открыть историю кодом. Устаревший код (MAC check) — свой ответ: он *был* верным, но его сменили. */
    suspend fun restore(code: String): Boolean {
        val key = code.trim()
        if (key.isEmpty() || mutableWorking.value) return false
        mutableWorking.value = true
        return try {
            withTimeLimit(SDK_LIMIT) { encryption.recover(key) }
            mutableProtection.value = HistoryProtection.ON
            true
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "код восстановления не подошёл: ${describe(error)}")
            failure = if (isStale(error)) AccountActionFailure.STALE_CODE else AccountActionFailure.RESTORE
            false
        } finally {
            mutableWorking.value = false
        }
    }

    /**
     * Дождаться, пока шифрование аккаунта уляжется после входа, — и прочитать состояние *заново*:
     * `load()` идемпотентен, и оставленное им значение — то, что было до ожидания.
     */
    suspend fun waitForSetup() {
        encryption.waitForE2eeInitializationTasks()
        load()
        mutableProtection.value = protection(encryption.recoveryState())
    }

    /** Есть ли у аккаунта другие устройства, с которых можно подтвердить это. */
    suspend fun hasOtherDevices(): Boolean = runCatching { withTimeLimit(SDK_LIMIT) { encryption.hasDevicesToVerifyAgainst() } }
        .onFailure { Log.error(Log.Category.SESSION, "не узнать, есть ли другие устройства: ${Log.describe(it)}") }
        .getOrDefault(false)

    /** Начать с чистого листа: выбросить старую личность. Сервер может попросить подтверждения. */
    suspend fun beginStartOver(): StartOverApproval {
        try {
            val handle = withTimeLimit(SDK_LIMIT) { encryption.resetIdentity() }
            reset = handle
            return when (val auth = handle?.authType()) {
                null -> StartOverApproval.None
                is CrossSigningResetAuthType.Uiaa -> StartOverApproval.Password
                is CrossSigningResetAuthType.OAuth -> StartOverApproval.Browser(auth.info.approvalUrl)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "сброс не начался: ${Log.describe(error)}")
            reset = null
            throw StartOverFailed()
        }
    }

    suspend fun finishStartOver(password: String? = null, userId: String? = null) {
        val handle = reset ?: return
        reset = null
        mutableWorking.value = true
        try {
            withTimeLimit(SDK_LIMIT) {
                if (password != null && userId != null) {
                    handle.reset(AuthData.Password(AuthDataPasswordDetails(userId, password)))
                } else {
                    handle.reset(null)
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "сброс не закончился: ${Log.describe(error)}")
            throw StartOverFailed()
        } finally {
            mutableWorking.value = false
        }
    }

    suspend fun cancelStartOver() {
        val handle = reset ?: return
        reset = null
        runCatching { withTimeLimit(SDK_LIMIT) { handle.cancel() } }
    }

    class StartOverFailed : Exception()

    private suspend fun <T> working(onFailure: AccountActionFailure, body: suspend () -> T): T? =
        working({ onFailure }, body)

    private suspend fun <T> working(failureOf: (Throwable) -> AccountActionFailure, body: suspend () -> T): T? {
        if (mutableWorking.value) return null
        mutableWorking.value = true
        return try {
            body()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val onFailure = failureOf(error)
            Log.error(Log.Category.SESSION, "восстановление: ${onFailure.name.lowercase()} не вышло: ${describe(error)}")
            failure = onFailure
            null
        } finally {
            mutableWorking.value = false
        }
    }

    internal companion object {
        /** Почему не включилась копия. `BackupExistsOnServer` — не сбой, а другое состояние аккаунта. */
        fun failureOfProtecting(error: Throwable): AccountActionFailure =
            if (error is RecoveryException.BackupExistsOnServer) AccountActionFailure.BACKUP_ELSEWHERE
            else AccountActionFailure.PROTECT_HISTORY

        private val SDK_LIMIT = 60.seconds

        /** Включение ждёт, пока ключи уйдут на сервер, — на большом аккаунте это минуты. */
        private val ENABLE_LIMIT = 5.minutes

        private fun protection(state: RecoveryState) = when (state) {
            RecoveryState.UNKNOWN -> HistoryProtection.UNKNOWN
            RecoveryState.ENABLED -> HistoryProtection.ON
            RecoveryState.DISABLED -> HistoryProtection.OFF
            RecoveryState.INCOMPLETE -> HistoryProtection.KEY_NEEDED
        }

        /** Код, которым это хранилище *было* открыто, но который с тех пор сменили, — «MAC check». */
        private fun isStale(error: Throwable): Boolean =
            error is RecoveryException.SecretStorage && error.message.orEmpty().contains("MAC check", ignoreCase = true)

        /** Вид ошибки; текст SDK — только для SecretStorage, где это техническая строка без содержимого. */
        private fun describe(error: Throwable): String = Log.describe(error)
    }
}
