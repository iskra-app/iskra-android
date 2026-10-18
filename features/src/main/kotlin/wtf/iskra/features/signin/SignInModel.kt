package wtf.iskra.features.signin

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import wtf.iskra.core.session.AuthenticationError
import wtf.iskra.core.session.SignInFlow
import wtf.iskra.core.session.SignInOptions
import wtf.iskra.core.support.Log
import wtf.iskra.features.R

/**
 * Поход в браузер и обратно. Возвращает callback URL или null, если человек закрыл браузер сам —
 * это не ошибка и не повод для сообщения.
 */
fun interface WebAuthentication {
    suspend fun run(url: String): String?
}

/**
 * Ведёт экран входа. Порт `SignInModel.swift`.
 *
 * Вход в Matrix по-настоящему сложен — три способа, сервер по доменному имени, поход в браузер, — и
 * ничто из этого не забота человека. Модель впитывает это и отдаёт три состояния: адрес; дверь
 * («Войти» и «Создать аккаунт» на найденном сервере); и только там, где двери мало, — «вот как войти».
 */
class SignInModel<S>(
    private val flow: SignInFlow<S>,
    private val browser: WebAuthentication,
    private val scope: CoroutineScope,
    private val onSignedIn: (S) -> Unit,
    address: String = "",
) {
    sealed interface Step {
        /** Первый экран: адрес сервера и «Далее». */
        data object EnteringAddress : Step

        /** Сервер ответил: две кнопки вместо поля. */
        data class Found(val options: SignInOptions) : Step

        /** Форма — там, где одной двери мало: сервер только с паролем или браузер, закрытый на полпути. */
        data class Ready(val options: SignInOptions) : Step
    }

    enum class Intent { SIGN_IN, CREATE_ACCOUNT }

    data class State(
        /** Начинается пустым: вопрос задаётся, а не решается за всех в пользу одного сервера. */
        val address: String = "",
        val step: Step = Step.EnteringAddress,
        val isBusy: Boolean = false,
        val pending: Intent? = null,
        /** Всегда предложение и всегда о том, что делать дальше. */
        @StringRes val problem: Int? = null,
        val username: String = "",
        val password: String = "",
    ) {
        val canContinue: Boolean get() = address.isNotBlank() && !isBusy
        val canSignInWithPassword: Boolean get() = !isBusy && username.isNotEmpty() && password.isNotEmpty()

        /** Имя сервера, как его показывает экран: то, что напечатано, без схемы и слэша. */
        val serverName: String
            get() = address.trim().let { name ->
                listOf("https://", "http://").firstOrNull { name.startsWith(it, ignoreCase = true) }
                    ?.let { name.substring(it.length) } ?: name
            }.trimEnd('/')

        val options: SignInOptions?
            get() = when (step) {
                Step.EnteringAddress -> null
                is Step.Found -> step.options
                is Step.Ready -> step.options
            }

        /** Пароль не печатается даже случайно. */
        override fun toString() = "State(step=$step, busy=$isBusy, problem=$problem)"
    }

    private val mutableState = MutableStateFlow(State(address = address))
    val state: StateFlow<State> = mutableState.asStateFlow()
    private var hasSignedIn = false

    fun setAddress(value: String) = mutableState.update { it.copy(address = value) }
    fun setUsername(value: String) = mutableState.update { it.copy(username = value) }
    fun setPassword(value: String) = mutableState.update { it.copy(password = value) }

    /** «Далее»: ищет напечатанный адрес и выясняет, как на нём войти. */
    fun findServer() {
        if (!state.value.canContinue) return
        scope.launch {
            lookUp()?.let { options -> mutableState.update { it.copy(step = Step.Found(options)) } }
        }
    }

    /**
     * Одна из двух кнопок найденного сервера. Вход на сервере со своей страницей входа ведёт прямо
     * туда; форму получает только сервер с одним паролем, и браузер, закрытый на полпути, приводит к
     * той же тихой второй странице. Сервер не ищется заново: попытка «Далее» ещё открыта.
     */
    fun begin(intent: Intent) {
        val current = state.value
        val options = current.options ?: return
        if (current.isBusy) return
        scope.launch {
            mutableState.update { it.copy(pending = intent) }
            try {
                when (intent) {
                    Intent.SIGN_IN -> if (options.canUseSingleSignOn) {
                        inBrowser { flow.singleSignOnUrl() }
                        val after = state.value
                        if (!hasSignedIn && (after.problem == null || options.supportsPassword)) {
                            mutableState.update { it.copy(step = Step.Ready(options)) }
                        }
                    } else {
                        mutableState.update { it.copy(step = Step.Ready(options)) }
                    }

                    Intent.CREATE_ACCOUNT -> if (!options.supportsRegistration) {
                        mutableState.update { it.copy(problem = R.string.sign_in_problem_no_registration) }
                    } else {
                        inBrowser { flow.registrationUrl() }
                    }
                }
            } finally {
                mutableState.update { it.copy(pending = null) }
            }
        }
    }

    /** Из формы назад к двум кнопкам. Сервер тот же, попытка остаётся. */
    fun back() {
        val options = state.value.options ?: return
        mutableState.update { it.copy(step = Step.Found(options), problem = null, username = "", password = "") }
    }

    fun continueInBrowser() {
        if (state.value.isBusy) return
        scope.launch { inBrowser { flow.singleSignOnUrl() } }
    }

    fun signInWithPassword() {
        val current = state.value
        if (!current.canSignInWithPassword) return
        scope.launch {
            mutableState.update { it.copy(isBusy = true, problem = null) }
            try {
                val session = flow.signIn(current.username, current.password)
                // Пароль своё сделал; в памяти он не остаётся ни на миг дольше.
                mutableState.update { it.copy(password = "") }
                finish(session)
            } catch (_: AuthenticationError.AlreadySignedIn) {
                alreadyOnTheDevice()
            } catch (error: Exception) {
                Log.info(Log.Category.SESSION, "вход по паролю не удался: ${Log.describe(error)}")
                mutableState.update { it.copy(isBusy = false, password = "", problem = R.string.sign_in_problem_bad_credentials) }
            }
        }
    }

    /** Назад к адресу, чтобы его поменять. Напечатанное остаётся — его исправляют, а не набирают заново. */
    fun startOver() {
        scope.launch {
            flow.cancelSignIn()
            mutableState.update {
                it.copy(step = Step.EnteringAddress, problem = null, username = "", password = "", isBusy = false)
            }
        }
    }

    private suspend fun lookUp(): SignInOptions? {
        mutableState.update { it.copy(isBusy = true, problem = null) }
        val problem = try {
            return flow.startSignIn(state.value.address.trim())
        } catch (_: AuthenticationError.HomeserverNotFound) {
            R.string.sign_in_problem_not_found
        } catch (_: AuthenticationError.HomeserverUnreachable) {
            R.string.sign_in_problem_unreachable
        } catch (_: AuthenticationError.NoSupportedSignInMethod) {
            R.string.sign_in_problem_unsupported_method
        } catch (_: AuthenticationError.StorageUnavailable) {
            // К напечатанному отношения не имеет — не отправлять человека искать опечатку, которой нет.
            R.string.sign_in_problem_storage_unavailable
        } catch (error: Exception) {
            Log.info(Log.Category.SESSION, "поиск сервера: ${Log.describe(error)}")
            R.string.sign_in_problem_unreachable
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
        mutableState.update { it.copy(problem = problem) }
        return null
    }

    /**
     * Поход в браузер, общий у входа и регистрации. Три шага различаются в журнале — человеку одно
     * предложение, потому что сделать он может одно, а разработчику три.
     */
    private suspend fun inBrowser(address: suspend () -> String) {
        mutableState.update { it.copy(isBusy = true, problem = null) }
        try {
            val url = try {
                address()
            } catch (error: Exception) {
                Log.error(Log.Category.SESSION, "не вышло понять, куда отправить браузер: ${Log.describe(error)}")
                mutableState.update { it.copy(problem = R.string.sign_in_problem_sso_incomplete) }
                return
            }
            val callback = try {
                browser.run(url) ?: return // человек закрыл браузер сам
            } catch (error: Exception) {
                // Ошибка, а не URL: в callback URL код авторизации.
                Log.error(Log.Category.SESSION, "браузер не вернулся: ${Log.describe(error)}")
                mutableState.update { it.copy(problem = R.string.sign_in_problem_sso_incomplete) }
                return
            }
            try {
                finish(flow.completeSingleSignOn(callback))
            } catch (_: AuthenticationError.AlreadySignedIn) {
                alreadyOnTheDevice()
            } catch (error: Exception) {
                Log.error(Log.Category.SESSION, "сервер не закончил вход: ${Log.describe(error)}")
                mutableState.update { it.copy(problem = R.string.sign_in_problem_sso_incomplete) }
            }
        } finally {
            mutableState.update { it.copy(isBusy = false) }
        }
    }

    /** Вошли в того, кто уже здесь. Попытки нет — экран возвращается к адресу и говорит почему. */
    private fun alreadyOnTheDevice() = mutableState.update {
        it.copy(step = Step.EnteringAddress, isBusy = false, username = "", password = "", problem = R.string.sign_in_problem_already_signed_in)
    }

    private fun finish(session: S) {
        hasSignedIn = true
        mutableState.update { it.copy(isBusy = false) }
        onSignedIn(session)
    }
}
