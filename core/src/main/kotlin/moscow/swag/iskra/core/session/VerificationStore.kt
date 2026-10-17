package moscow.swag.iskra.core.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moscow.swag.iskra.core.support.Log
import moscow.swag.iskra.core.support.withTimeLimit
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails
import kotlin.time.Duration.Companion.seconds

/**
 * Доказать, что устройство правда твоё, сравнив картинки с тем, которое уже признано, — в обе
 * стороны. Порт `VerificationStore` (`Verification.swift`); вся криптография — SDK, здесь — машина
 * состояний и список эмодзи.
 *
 * Своё устройство просит: `requestDeviceVerification` → другое приняло → *мы* начинаем SAS → эмодзи →
 * человек сравнивает. Чужое просит нас: показать, *какое* устройство, — автоматического «да» нет: в
 * узнавании имени и вся безопасность шага. Начинает сравнение сторона, которая просила; согласившаяся
 * ждёт, иначе обе пошлют `start`.
 *
 * Живёт с сеансом, а не с экраном: входящий запрос — то, что случается с человеком, пока он читает чат.
 */
class VerificationStore internal constructor(
    private val client: Client,
    private val ownUserId: String,
    private val scope: CoroutineScope,
) {
    /** Своё устройство просит доверия. Имя — то, о чём человека и спрашивают; ID — под ним. */
    data class IncomingRequest(val flowId: String, val senderId: String, val deviceId: String, val deviceName: String?)

    sealed interface Step {
        data object Idle : Step

        /** Своё другое устройство просит доверия, человек ещё не ответил. */
        data object Asked : Step

        /** Человек согласился, устройства договариваются. */
        data object Negotiating : Step

        /** Попросили — ждём, пока заметят на другом устройстве. */
        data object WaitingForOtherDevice : Step

        /** Эмодзи на экране — совпадают ли. */
        data class Comparing(val symbols: List<String>) : Step
        data object Succeeded : Step
        data object Failed : Step

        /** Кто-то сказал «нет» — не сбой, и слова другие. */
        data object Declined : Step

        /** Человек сказал, что устройство не его: *кто-то другой входит с его паролем*. */
        data object Refused : Step
    }

    private val mutableStep = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = mutableStep.asStateFlow()

    private val mutableIncoming = MutableStateFlow<IncomingRequest?>(null)

    /** Не null — и лист поверх всего приложения, где бы человек ни был. */
    val incoming: StateFlow<IncomingRequest?> = mutableIncoming.asStateFlow()

    private val mutableAnswered = MutableStateFlow(false)

    /**
     * Ответ дан и ушёл. Шаг не двигается, пока не ответит *другое* устройство, — и кнопки до тех пор
     * не должны снова выглядеть ждущими нажатия.
     */
    val hasAnswered: StateFlow<Boolean> = mutableAnswered.asStateFlow()

    private var isIncoming = false
    private var isWorking = false
    private var controller: SessionVerificationController? = null

    private fun move(step: Step) {
        if (step != mutableStep.value) mutableAnswered.value = false
        mutableStep.value = step
    }

    /** Слушать запросы. Раз, когда сеанс начался; неудача переживаема и не показывается. */
    suspend fun listen() {
        if (controller != null) return
        try {
            val found = withTimeLimit(LIMIT) { client.getSessionVerificationController() }
            controller = found
            found.setDelegate(Delegate())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "подтверждение недоступно: ${Log.describe(error)}")
        }
    }

    /** Попросить другие устройства аккаунта подтвердить это. */
    suspend fun start() {
        val step = mutableStep.value
        if (isWorking || (step != Step.Idle && step != Step.Failed && step != Step.Declined)) return
        isWorking = true
        try {
            listen()
            val controller = controller ?: return move(Step.Failed)
            isIncoming = false
            move(Step.WaitingForOtherDevice)
            withTimeLimit(LIMIT) { controller.requestDeviceVerification() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "подтверждение не началось: ${Log.describe(error)}")
            move(Step.Failed)
        } finally {
            isWorking = false
        }
    }

    /** «Это я». Ещё ничего не даёт — соглашается *посмотреть*; доверие — после сравнения. */
    suspend fun accept() {
        val request = mutableIncoming.value ?: return
        if (mutableStep.value != Step.Asked || isWorking || mutableAnswered.value) return
        mutableAnswered.value = true
        isWorking = true
        isIncoming = true
        try {
            // Два вызова, и первый — не формальность: SDK не следит ни за каким запросом, пока ему
            // не сказали, за каким.
            withTimeLimit(LIMIT) { controller?.acknowledgeVerificationRequest(request.senderId, request.flowId) }
            move(Step.Negotiating)
            withTimeLimit(LIMIT) { controller?.acceptVerificationRequest() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "подтверждение не принялось: ${Log.describe(error)}")
            move(Step.Failed)
        } finally {
            isWorking = false
        }
    }

    /** «Это не я». Другая сторона узнаёт, а человек — что это значит. */
    suspend fun refuse() {
        val request = mutableIncoming.value ?: return
        if (mutableStep.value != Step.Asked || isWorking || mutableAnswered.value) return
        mutableAnswered.value = true
        runCatching { withTimeLimit(LIMIT) { controller?.acknowledgeVerificationRequest(request.senderId, request.flowId) } }
        runCatching { withTimeLimit(LIMIT) { controller?.cancelVerification() } }
        move(Step.Refused)
    }

    /** Картинки одинаковые — единственный момент, где «да» человека — криптографическое утверждение. */
    suspend fun approve() {
        if (mutableStep.value !is Step.Comparing || mutableAnswered.value) return
        mutableAnswered.value = true
        try {
            withTimeLimit(LIMIT) { controller?.approveVerification() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.SESSION, "подтверждение не завершилось: ${Log.describe(error)}")
            move(Step.Failed)
        }
    }

    /** Не одинаковые: кто-то посередине — или, куда вероятнее, начато дважды. Ответ — нет. */
    suspend fun decline() {
        if (mutableStep.value !is Step.Comparing || mutableAnswered.value) return
        mutableAnswered.value = true
        runCatching { withTimeLimit(LIMIT) { controller?.declineVerification() } }
        move(Step.Declined)
    }

    /** Закрыть лист. Живое подтверждение отменяется — другое устройство ждёт ответа; законченное — нет. */
    suspend fun cancel() {
        when (mutableStep.value) {
            Step.Asked, Step.Negotiating, Step.WaitingForOtherDevice, is Step.Comparing ->
                runCatching { withTimeLimit(LIMIT) { controller?.cancelVerification() } }
            else -> Unit
        }
        reset()
    }

    fun reset() {
        move(Step.Idle)
        mutableIncoming.value = null
        isIncoming = false
    }

    fun stop() {
        runCatching { controller?.setDelegate(null) }
        controller = null
        reset()
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Requested -> {
                if (!shouldShow(event.request, ownUserId, mutableStep.value)) {
                    Log.info(Log.Category.SESSION, "запрос подтверждения оставлен без ответа")
                    return
                }
                mutableIncoming.value = event.request
                move(Step.Asked)
            }
            // Запрос готов, и сказано обеим сторонам. Сравнение начинает та, что просила.
            Event.Accepted -> if (!isIncoming) {
                scope.launch {
                    try {
                        withTimeLimit(LIMIT) { controller?.startSasVerification() }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Log.error(Log.Category.SESSION, "сравнение не началось: ${Log.describe(error)}")
                        move(Step.Failed)
                    }
                }
            }
            Event.SasStarted -> Unit
            is Event.Data -> move(Step.Comparing(event.symbols))
            Event.Failed -> move(Step.Failed)
            Event.Cancelled -> when (mutableStep.value) {
                // Другое устройство передумало, пока человек не ответил: ничего не случилось.
                Step.Asked -> reset()
                // SDK шлёт отмену после «нет» — не затирать ответ, который различает экран.
                Step.Declined, Step.Refused, Step.Succeeded -> Unit
                else -> move(Step.Declined)
            }
            Event.Finished -> move(Step.Succeeded)
        }
    }

    private sealed interface Event {
        data class Requested(val request: IncomingRequest) : Event
        data object Accepted : Event
        data object SasStarted : Event
        data class Data(val symbols: List<String>) : Event
        data object Failed : Event
        data object Cancelled : Event
        data object Finished : Event
    }

    private inner class Delegate : SessionVerificationControllerDelegate {
        private fun send(event: Event) {
            scope.launch { handle(event) }
        }

        /** Передаётся дальше — и *не* принимается здесь: решает человек, а не программа. */
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) =
            send(Event.Requested(IncomingRequest(details.flowId, details.senderProfile.userId, details.deviceId, details.deviceDisplayName)))

        override fun didAcceptVerificationRequest() = send(Event.Accepted)
        override fun didStartSasVerification() = send(Event.SasStarted)

        override fun didReceiveVerificationData(data: SessionVerificationData) {
            when (data) {
                // Только символы: имена эмодзи SDK отдаёт по-английски, а сравнивают их глазами.
                is SessionVerificationData.Emojis -> send(Event.Data(data.emojis.map { it.symbol() }))
                is SessionVerificationData.Decimals -> send(Event.Data(data.values.map { it.toString() }))
            }
        }

        override fun didFail() = send(Event.Failed)
        override fun didCancel() = send(Event.Cancelled)
        override fun didFinish() = send(Event.Finished)
    }

    companion object {
        private val LIMIT = 60.seconds

        /**
         * Показывать ли пришедший запрос. Чужой — другая функция («правда ли это Борис?»), которой нет;
         * второй посреди ответа подменил бы устройство *под кнопками*. Обе — решения о безопасности,
         * поэтому функция аргументов и под тестом.
         */
        fun shouldShow(request: IncomingRequest, ownUserId: String, step: Step): Boolean =
            request.senderId == ownUserId && step == Step.Idle
    }
}
