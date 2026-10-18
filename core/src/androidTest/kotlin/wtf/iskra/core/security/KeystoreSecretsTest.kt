package wtf.iskra.core.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Настоящий Keystore: на JVM хоста его нет, а устройства различаются (docs/PROMPT.md, 5.3). */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretsTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "secrets-test").apply { deleteRecursively() }
    private val secrets = KeystoreSecrets(dir, alias = "wtf.iskra.test")

    @Test
    fun roundTrip() {
        val value = "токен".encodeToByteArray()
        secrets.write("session.a", value)
        assertContentEquals(value, secrets.read("session.a"))
        assertFalse(File(dir, "session.a.bin").readBytes().decodeToString().contains("токен"), "на диске открытый текст")
        secrets.remove("session.a")
        assertNull(secrets.read("session.a"))
    }

    /** Имя ключа — в AAD: файл одного секрета под именем другого не расшифруется. */
    @Test
    fun aFileMovedUnderAnotherNameDoesNotDecrypt() {
        secrets.write("session.a", byteArrayOf(1, 2, 3))
        File(dir, "session.a.bin").copyTo(File(dir, "session.b.bin"), overwrite = true)
        assertFailsWith<SecretStorageException> { secrets.read("session.b") }
    }
}
