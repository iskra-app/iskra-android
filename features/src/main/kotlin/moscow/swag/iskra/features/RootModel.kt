package moscow.swag.iskra.features

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import moscow.swag.iskra.core.session.AuthenticationError
import moscow.swag.iskra.core.session.AuthenticationService
import moscow.swag.iskra.core.session.UserSession
import moscow.swag.iskra.core.support.Log
import moscow.swag.iskra.features.signin.SignInModel
import moscow.swag.iskra.features.signin.WebAuthentication

/**
 * Корень приложения: в какой фазе оно сейчас. Порт фаз и `restore()` из `IskraRootView.swift`
 * (docs/PROMPT.md, 15).
 *
 * Восстановление и вход — явные вызовы модели, а не побочный эффект composable, который кто-нибудь
 * разумно удалит (та же ошибка, что опустошила ленты на iOS, раздел 4.2).
 */
class RootModel(
    private val authentication: AuthenticationService,
    private val scope: CoroutineScope,
) {
    sealed interface Phase {
        data object Starting : Phase

        /** Никого нет. [endedRemotely] — сеанс завершили где-то ещё, и это нужно *сказать*. */
        data class SignedOut(val endedRemotely: Boolean = false) : Phase

        /**
         * Вошли, а сеанс не поднялся, потому что нет сети. Своя фаза: два способа не получить сеанс
         * при запуске — не одно и то же и не должны выглядеть одинаково. Экран входа здесь сказал бы
         * человеку, что аккаунта больше нет, а это неправда и страшно.
         */
        data object Offline : Phase

        data class SignedIn(val session: UserSession) : Phase
    }

    private val mutablePhase = MutableStateFlow<Phase>(Phase.Starting)
    val phase: StateFlow<Phase> = mutablePhase.asStateFlow()

    init {
        restore()
    }

    /** Модель экрана входа: живёт столько, сколько фаза «никого нет». */
    fun signInModel(browser: WebAuthentication): SignInModel<UserSession> =
        SignInModel(authentication, browser, scope, onSignedIn = { mutablePhase.value = Phase.SignedIn(it) })

    /** «Повторить» на экране без сети: та же попытка, никто не разлогинен. */
    fun retry() {
        if (mutablePhase.value != Phase.Offline) return
        mutablePhase.value = Phase.Starting
        restore()
    }

    fun signOut() {
        val session = (mutablePhase.value as? Phase.SignedIn)?.session ?: return
        scope.launch {
            session.signOut()
            mutablePhase.value = Phase.SignedOut()
            // Если на устройстве есть ещё аккаунты — открыть следующий, а не показывать вход.
            if (authentication.accounts().isNotEmpty()) {
                mutablePhase.value = Phase.Starting
                restore()
            }
        }
    }

    /**
     * Возвращает вошедшего, если он есть, прежде чем что-то показывать. Аккаунт, бывший на экране
     * последним, потом остальные по порядку: один не открывшийся аккаунт — не повод прятать
     * остальных за экраном входа.
     */
    private fun restore() = scope.launch {
        // Чьё-то брошенное: сторы входов, которые не закончились, и сеансов, которые заменили. Они
        // читаемы — пароль стабилен, — так что это не мусор, а копия переписки, на которую ничто не
        // указывает. Безопасно сказать это можно только сейчас.
        authentication.sweepOrphanedStores()

        val accounts = authentication.accounts()
        val active = authentication.activeAccountId()
        val order = listOfNotNull(active) + accounts.map { it.sessionId }.filter { it != active }
        var ended = false

        for (id in order) {
            try {
                val session = authentication.restoreSession(id) ?: continue
                authentication.setActiveAccount(id)
                mutablePhase.value = Phase.SignedIn(session)
                return@launch
            } catch (_: AuthenticationError.NetworkUnavailable) {
                // Никогда не экран входа. Сеть одна на все аккаунты, следующего пробовать незачем.
                mutablePhase.value = Phase.Offline
                return@launch
            } catch (_: AuthenticationError.SessionEnded) {
                // Сервер сказал, что аккаунт окончен, — единственный отказ, который это решает.
                ended = true
                authentication.forgetSession(id)
            } catch (error: Exception) {
                // Оставлен, а не забыт: стор, который сегодня не открылся, — не повод его уничтожать.
                // Повторный вход в тот же аккаунт заменяет запись — так она и чинится.
                Log.error(Log.Category.SESSION, "аккаунт не открылся: ${Log.describe(error)}")
            }
        }
        mutablePhase.value = Phase.SignedOut(endedRemotely = ended)
    }
}
