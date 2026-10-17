package moscow.swag.iskra.core.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Какие запросы подтверждения доходят до человека. Порт `VerificationTests`: оба отказа — решения о безопасности. */
class VerificationTest {
    private val me = "@boris:matrix.org"

    private fun request(sender: String = me, device: String = "ABCDEFGH") =
        VerificationStore.IncomingRequest("flow", sender, device, "Pixel")

    @Test
    fun aDeviceOfYourOwnWhenNothingElseIsHappeningIsWorthAskingAbout() {
        assertTrue(VerificationStore.shouldShow(request(), me, VerificationStore.Step.Idle))
    }

    @Test
    fun somebodyElsesRequestIsNotDressedUpAsYourOwnDevice() {
        assertFalse(VerificationStore.shouldShow(request(sender = "@eve:matrix.org"), me, VerificationStore.Step.Idle))
    }

    @Test
    fun aSecondRequestDoesNotSwapTheDeviceOutFromUnderTheButtons() {
        val steps = listOf(
            VerificationStore.Step.Asked, VerificationStore.Step.Negotiating, VerificationStore.Step.WaitingForOtherDevice,
            VerificationStore.Step.Comparing(listOf("🐶")), VerificationStore.Step.Succeeded, VerificationStore.Step.Failed,
            VerificationStore.Step.Declined, VerificationStore.Step.Refused,
        )
        for (step in steps) assertFalse(VerificationStore.shouldShow(request(device = "ZZZZZZZZ"), me, step), "$step")
    }
}
