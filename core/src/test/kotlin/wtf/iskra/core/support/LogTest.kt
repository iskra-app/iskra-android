package wtf.iskra.core.support

import org.matrix.rustcomponents.sdk.ClientException
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Порт `LogTests`: в журнал идёт вид ошибки, а не то, что она несёт. */
class LogTest {
    @Test
    fun theServersWordsStayOut() {
        val described = Log.describe(ClientException.Generic("Пароль hunter2 неверен", "details"))
        assertEquals("ClientException.Generic", described)
        assertFalse("hunter2" in described)
    }

    @Test
    fun theUsersFileNameStaysOut() {
        val described = Log.describe(FileNotFoundException("/storage/паспорт_скан.pdf"))
        assertEquals("FileNotFoundException", described)
    }
}
