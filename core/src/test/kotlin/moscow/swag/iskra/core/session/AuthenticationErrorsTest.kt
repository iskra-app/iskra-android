package moscow.swag.iskra.core.session

import moscow.swag.iskra.core.support.TimeoutError
import org.matrix.rustcomponents.sdk.ClientBuildException
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * От того, как названа ошибка восстановления, зависит, останется ли у человека аккаунт: офлайн — не
 * выход, мёртвый токен — выход. `Generic` намеренно ни то, ни другое.
 */
class AuthenticationErrorsTest {
    @Test
    fun noNetworkIsNotSignedOut() {
        assertTrue(AuthenticationService.isNetworkFailure(TimeoutError(20.seconds)))
        assertTrue(AuthenticationService.isNetworkFailure(ClientBuildException.ServerUnreachable("x")))
        assertTrue(AuthenticationService.isNetworkFailure(ClientBuildException.WellKnownLookupFailed("x")))
        assertFalse(AuthenticationService.isNetworkFailure(ClientException.Generic("x", null)))
    }

    @Test
    fun onlyUnknownTokenEndsTheSession() {
        assertTrue(AuthenticationService.isDeadToken(ClientException.MatrixApi(ErrorKind.UnknownToken(false), "M_UNKNOWN_TOKEN", "x", null)))
        assertFalse(AuthenticationService.isDeadToken(ClientException.MatrixApi(ErrorKind.Forbidden, "M_FORBIDDEN", "x", null)))
        assertFalse(AuthenticationService.isDeadToken(ClientException.Generic("x", null)))
    }
}
