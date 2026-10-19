package wtf.iskra.features

import wtf.iskra.core.session.AccountActionFailure
import wtf.iskra.core.session.HistoryProtection
import wtf.iskra.features.recovery.RecoverySetupModel
import kotlin.test.Test
import kotlin.test.assertEquals

/** Куда ведёт настройка восстановления по состоянию копии. Порт `RecoverySetupTests`. */
class RecoveryRouteTest {
    @Test
    fun aNewAccountGetsABackupAndALockedDeviceGetsAChoice() {
        assertEquals(RecoverySetupModel.Step.CREATING, RecoverySetupModel.route(HistoryProtection.OFF))
        assertEquals(RecoverySetupModel.Step.CHOOSE, RecoverySetupModel.route(HistoryProtection.KEY_NEEDED))
    }

    /** «Не знаю» — не повод действовать: пустить в приложение обратимо, угадать — нет. */
    @Test
    fun unknownAndOnAskNothing() {
        assertEquals(RecoverySetupModel.Step.DONE, RecoverySetupModel.route(HistoryProtection.ON))
        assertEquals(RecoverySetupModel.Step.DONE, RecoverySetupModel.route(HistoryProtection.UNKNOWN))
    }

    /**
     * Копию сделало другое устройство (`BackupExistsOnServer`): история не защищена и кода нет — это
     * говорится словами, с путём дальше, а не «пускаем в приложение» молча.
     */
    @Test
    fun aBackupFromAnotherDeviceIsSaidOutLoud() {
        assertEquals(RecoverySetupModel.Step.ELSEWHERE, RecoverySetupModel.afterFailedProtect(AccountActionFailure.BACKUP_ELSEWHERE))
    }

    /** Прочие сбои пускают в приложение — баннер и настройки попросят снова; сбой показан до ухода. */
    @Test
    fun anOrdinaryFailureLetsYouIn() {
        assertEquals(RecoverySetupModel.Step.DONE, RecoverySetupModel.afterFailedProtect(AccountActionFailure.PROTECT_HISTORY))
        assertEquals(RecoverySetupModel.Step.DONE, RecoverySetupModel.afterFailedProtect(null))
    }
}
