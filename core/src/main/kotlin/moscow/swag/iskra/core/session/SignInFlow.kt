package moscow.swag.iskra.core.session

/**
 * Вход глазами экрана: найти сервер, войти, бросить. Интерфейс — чтобы модель экрана проверялась
 * без SDK; в приложении это всегда [AuthenticationService], а [S] — [UserSession].
 */
interface SignInFlow<out S> {
    suspend fun startSignIn(address: String): SignInOptions
    suspend fun singleSignOnUrl(): String
    suspend fun registrationUrl(): String
    suspend fun completeSingleSignOn(callbackUrl: String): S
    suspend fun signIn(username: String, password: String): S
    suspend fun cancelSignIn()
}
