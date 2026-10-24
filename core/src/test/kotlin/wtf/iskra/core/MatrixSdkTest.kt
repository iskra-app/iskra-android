package wtf.iskra.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MatrixSdkTest {
    @Test
    fun shortShaOfTheSameCommitMatches() {
        assertTrue(MatrixSdk.isExpectedCommit("15a3ce236"))
        assertTrue(MatrixSdk.isExpectedCommit(MatrixSdk.EXPECTED_COMMIT))
    }

    @Test
    fun otherOrTooShortShaDoesNot() {
        assertFalse(MatrixSdk.isExpectedCommit("90db5fe38"))
        assertFalse(MatrixSdk.isExpectedCommit("15a"))
        assertFalse(MatrixSdk.isExpectedCommit(""))
    }
}
