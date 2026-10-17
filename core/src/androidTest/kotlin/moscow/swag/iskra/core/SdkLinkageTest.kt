package moscow.swag.iskra.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SDK линкуется и отвечает (docs/PROMPT.md, 20, этап 1). На JVM хоста это не проверить: в артефакте
 * только `.so` под ABI Android. Заодно — что это тот же коммит SDK, что у iOS.
 */
@RunWith(AndroidJUnit4::class)
class SdkLinkageTest {
    @Test
    fun sdkLoadsAndIsTheSameCommitAsIos() {
        assertEquals(MatrixSdk.EXPECTED_COMMIT, MatrixSdk.commit())
    }
}
