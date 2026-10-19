package wtf.iskra.core.session

import org.matrix.rustcomponents.sdk.RecoveryException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Почему не включилась резервная копия: чужая копия на сервере — своё состояние, а не «попробуйте ещё раз». */
class RecoveryFailureTest {
    @Test
    fun aBackupAlreadyOnTheServerIsNotAnOrdinaryFailure() {
        assertEquals(
            AccountActionFailure.BACKUP_ELSEWHERE,
            RecoveryStore.failureOfProtecting(RecoveryException.BackupExistsOnServer()),
        )
    }

    @Test
    fun anythingElseIsAskedAgain() {
        assertEquals(AccountActionFailure.PROTECT_HISTORY, RecoveryStore.failureOfProtecting(RecoveryException.SecretStorage("boom")))
        assertEquals(AccountActionFailure.PROTECT_HISTORY, RecoveryStore.failureOfProtecting(IllegalStateException()))
    }
}
