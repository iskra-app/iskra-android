package wtf.iskra.core.support

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnKeyTest {
    @Test
    fun plainReturnSendsByDefault() {
        assertEquals(ReturnKey.Action.SEND, ReturnKey.action(sendsWithCtrl = false, ctrl = false, shift = false))
    }

    @Test
    fun shiftReturnIsANewlineEitherWay() {
        assertEquals(ReturnKey.Action.NEWLINE, ReturnKey.action(sendsWithCtrl = false, ctrl = false, shift = true))
        assertEquals(ReturnKey.Action.NEWLINE, ReturnKey.action(sendsWithCtrl = true, ctrl = false, shift = true))
    }

    @Test
    fun withTheSettingOnReturnIsANewlineAndCtrlReturnSends() {
        assertEquals(ReturnKey.Action.NEWLINE, ReturnKey.action(sendsWithCtrl = true, ctrl = false, shift = false))
        assertEquals(ReturnKey.Action.SEND, ReturnKey.action(sendsWithCtrl = true, ctrl = true, shift = false))
    }

    @Test
    fun ctrlReturnAlwaysSends() {
        assertEquals(ReturnKey.Action.SEND, ReturnKey.action(sendsWithCtrl = false, ctrl = true, shift = false))
    }
}
