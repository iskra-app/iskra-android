package wtf.iskra.features.signin

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import wtf.iskra.core.session.AuthenticationError
import wtf.iskra.core.session.SignInFlow
import wtf.iskra.core.session.SignInOptions
import wtf.iskra.features.R
import wtf.iskra.features.signin.SignInModel.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SignInModelTest {
    private val oidc = SignInOptions(true, false, false, true, "https://matrix-client.example.org")
    private val passwordOnly = SignInOptions(false, false, true, false, "https://example.org")

    private class FakeFlow(var options: SignInOptions) : SignInFlow<String> {
        var lookups = mutableListOf<String>()
        var failLookup: Exception? = null
        var cancelled = 0
        var completedWith: String? = null
        var passwordOk = true
        var alreadyHere = false

        override suspend fun startSignIn(address: String): SignInOptions {
            lookups += address
            failLookup?.let { throw it }
            return options
        }
        override suspend fun singleSignOnUrl() = "https://auth.example.org/login"
        override suspend fun registrationUrl() = "https://auth.example.org/register"
        override suspend fun completeSingleSignOn(callbackUrl: String): String {
            if (alreadyHere) throw AuthenticationError.AlreadySignedIn()
            completedWith = callbackUrl
            return "session"
        }
        override suspend fun signIn(username: String, password: String): String {
            if (!passwordOk) throw AuthenticationError.Sdk(RuntimeException())
            return "session:$username"
        }
        override suspend fun cancelSignIn() { cancelled++ }
    }

    private fun TestScope.model(flow: FakeFlow, browserAnswer: String? = "wtf.iskra:/oauth-callback?code=x", signedIn: MutableList<String> = mutableListOf()) =
        SignInModel(flow, { browserAnswer }, TestScope(UnconfinedTestDispatcher(testScheduler)), { signedIn += it })

    @Test
    fun findingTheServerShowsTheDoor() = runTest {
        val flow = FakeFlow(oidc)
        val model = model(flow)
        model.setAddress("  example.org ")
        model.findServer()
        assertEquals(listOf("example.org"), flow.lookups)
        assertEquals(Step.Found(oidc), model.state.value.step)
        assertEquals("example.org", model.state.value.serverName)
    }

    @Test
    fun nothingTypedNothingLookedUp() = runTest {
        val flow = FakeFlow(oidc)
        model(flow).findServer()
        assertTrue(flow.lookups.isEmpty())
    }

    /** Беда с хранилищем не отправляет человека искать опечатку. */
    @Test
    fun eachFailureGetsItsOwnSentence() = runTest {
        val cases = mapOf(
            AuthenticationError.HomeserverNotFound() to R.string.sign_in_problem_not_found,
            AuthenticationError.HomeserverUnreachable() to R.string.sign_in_problem_unreachable,
            AuthenticationError.StorageUnavailable(RuntimeException()) to R.string.sign_in_problem_storage_unavailable,
            AuthenticationError.NoSupportedSignInMethod() to R.string.sign_in_problem_unsupported_method,
        )
        for ((error, sentence) in cases) {
            val flow = FakeFlow(oidc).apply { failLookup = error }
            val model = model(flow)
            model.setAddress("example.org")
            model.findServer()
            assertEquals(sentence, model.state.value.problem)
            assertEquals(Step.EnteringAddress, model.state.value.step)
            assertEquals(false, model.state.value.isBusy)
        }
    }

    @Test
    fun signInGoesStraightToTheBrowser() = runTest {
        val flow = FakeFlow(oidc)
        val signedIn = mutableListOf<String>()
        val model = model(flow, signedIn = signedIn)
        model.setAddress("example.org")
        model.findServer()
        model.begin(SignInModel.Intent.SIGN_IN)
        assertEquals(listOf("session"), signedIn)
        assertEquals("wtf.iskra:/oauth-callback?code=x", flow.completedWith)
    }

    /** Закрытый браузер — не ошибка: ни слова, и тихая вторая страница с дорогой обратно. */
    @Test
    fun closingTheBrowserIsNotAnError() = runTest {
        val flow = FakeFlow(oidc)
        val model = model(flow, browserAnswer = null)
        model.setAddress("example.org")
        model.findServer()
        model.begin(SignInModel.Intent.SIGN_IN)
        assertNull(model.state.value.problem)
        assertEquals(Step.Ready(oidc), model.state.value.step)
    }

    @Test
    fun passwordOnlyServerGetsTheForm() = runTest {
        val flow = FakeFlow(passwordOnly)
        val signedIn = mutableListOf<String>()
        val model = model(flow, signedIn = signedIn)
        model.setAddress("example.org")
        model.findServer()
        model.begin(SignInModel.Intent.SIGN_IN)
        assertEquals(Step.Ready(passwordOnly), model.state.value.step)
        model.setUsername("ann")
        model.setPassword("hunter2")
        model.signInWithPassword()
        assertEquals(listOf("session:ann"), signedIn)
        assertEquals("", model.state.value.password, "пароль не остаётся в памяти")
    }

    @Test
    fun wrongPasswordIsForgottenAndSaid() = runTest {
        val flow = FakeFlow(passwordOnly).apply { passwordOk = false }
        val model = model(flow)
        model.setAddress("example.org")
        model.findServer()
        model.begin(SignInModel.Intent.SIGN_IN)
        model.setUsername("ann")
        model.setPassword("hunter2")
        model.signInWithPassword()
        assertEquals(R.string.sign_in_problem_bad_credentials, model.state.value.problem)
        assertEquals("", model.state.value.password)
        assertTrue("hunter2" !in model.state.value.toString())
    }

    @Test
    fun registrationIsNotOfferedWhereTheServerCannot() = runTest {
        val flow = FakeFlow(passwordOnly)
        val model = model(flow)
        model.setAddress("example.org")
        model.findServer()
        model.begin(SignInModel.Intent.CREATE_ACCOUNT)
        assertEquals(R.string.sign_in_problem_no_registration, model.state.value.problem)
    }

    @Test
    fun theSamePersonTwiceGoesBackToTheAddress() = runTest {
        val flow = FakeFlow(oidc).apply { alreadyHere = true }
        val model = model(flow)
        model.setAddress("example.org")
        model.findServer()
        model.begin(SignInModel.Intent.SIGN_IN)
        assertEquals(Step.EnteringAddress, model.state.value.step)
        assertEquals(R.string.sign_in_problem_already_signed_in, model.state.value.problem)
    }

    @Test
    fun startingOverCancelsTheAttemptAndKeepsTheAddress() = runTest {
        val flow = FakeFlow(oidc)
        val model = model(flow)
        model.setAddress("example.org")
        model.findServer()
        model.startOver()
        assertEquals(1, flow.cancelled)
        assertEquals(Step.EnteringAddress, model.state.value.step)
        assertEquals("example.org", model.state.value.address)
    }
}
