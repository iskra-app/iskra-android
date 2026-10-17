package moscow.swag.iskra.features

import moscow.swag.iskra.core.session.HistoryProtection
import moscow.swag.iskra.features.recovery.RecoverySetupModel
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
}
