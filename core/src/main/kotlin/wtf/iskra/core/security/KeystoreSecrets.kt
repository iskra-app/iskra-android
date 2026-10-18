package wtf.iskra.core.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import wtf.iskra.core.support.Log
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Секреты в файлах приложения, зашифрованные ключом из Android Keystore (docs/PROMPT.md, 5.1).
 *
 * **Ключ.** AES-256-GCM, неэкспортируемый, в StrongBox, если он есть. **Без**
 * `setUserAuthenticationRequired` и без `setUnlockedDeviceRequired`: пуш приходит на заблокированный
 * телефон, и расшифровать его без токена и пароля стора нельзя. Это аналог
 * `AfterFirstUnlockThisDeviceOnly` на iOS: файлы лежат в credential-encrypted storage и недоступны до
 * первой разблокировки после загрузки, а бэкап выключен — ключ из Keystore с устройства не уходит,
 * и записи без него бесполезны.
 *
 * **Записи.** Одна запись — один файл `<dir>/<ключ>.bin`: версия, 12 байт IV, шифротекст с тегом.
 * Имя ключа идёт в AAD, поэтому файл одного секрета, подложенный под имя другого, не расшифруется.
 * Запись атомарна (временный файл и rename): оборванная запись не оставляет полсекрета.
 *
 * **Почему не Tink.** В задании Tink назван как вариант. Здесь он не нужен: шифрование — один вызов
 * `Cipher` AES-GCM ключом Keystore, а клиент Keystore в Tink не даёт попросить StrongBox. Меньше
 * кода между секретом и железом — меньше мест, где можно ошибиться. Формат версионирован, и перейти
 * можно будет без потери данных.
 */
class KeystoreSecrets(
    private val directory: File,
    private val alias: String = DEFAULT_ALIAS,
) : SecretStoring {

    override fun read(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.exists()) return null
        val blob = file.readBytes()
        if (blob.size < 1 + IV_BYTES || blob[0] != VERSION) {
            throw SecretStorageException("запись $key в неизвестном формате")
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, blob, 1, IV_BYTES))
            cipher.updateAAD(key.toByteArray())
            cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES)
        } catch (error: Exception) {
            throw SecretStorageException("запись $key не расшифровалась: ${Log.describe(error)}", error)
        }
    }

    override fun write(key: String, value: ByteArray) {
        val blob = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(key.toByteArray())
            val sealed = cipher.doFinal(value)
            val iv = cipher.iv
            check(iv.size == IV_BYTES)
            byteArrayOf(VERSION) + iv + sealed
        } catch (error: Exception) {
            throw SecretStorageException("запись $key не зашифровалась: ${Log.describe(error)}", error)
        }
        directory.mkdirs()
        val target = fileFor(key)
        val temporary = File(directory, "${target.name}.${random.nextLong().toULong()}.tmp")
        temporary.writeBytes(blob)
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw SecretStorageException("запись $key не легла на диск")
        }
    }

    override fun remove(key: String) {
        fileFor(key).delete()
    }

    /** Все записи. Ключ Keystore остаётся: он ничего не открывает без записей. */
    override fun removeAll() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(key: String): File {
        require(key.matches(SAFE_KEY)) { "имя секрета должно быть безопасным именем файла" }
        return File(directory, "$key.bin")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return synchronized(KeystoreSecrets::class.java) {
            (store.getKey(alias, null) as? SecretKey) ?: generate()
        }
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey().also { Log.info(Log.Category.SESSION, "ключ секретов создан в StrongBox") }
        } catch (_: StrongBoxUnavailableException) {
            generator.init(spec(strongBox = false))
            generator.generateKey().also { Log.info(Log.Category.SESSION, "ключ секретов создан в Keystore (StrongBox нет)") }
        }
    }

    /** Есть ли у ключа аппаратная защита — для пробы `verify-storage`. */
    fun describeKey(): String {
        val secretKey = key()
        val factory = javax.crypto.SecretKeyFactory.getInstance(secretKey.algorithm, KEYSTORE)
        val info = factory.getKeySpec(secretKey, android.security.keystore.KeyInfo::class.java) as android.security.keystore.KeyInfo
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
                else -> "unknown(${info.securityLevel})"
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) "secure hardware" else "software"
        }
        return "AES-${info.keySize}, $level"
    }

    companion object {
        const val DEFAULT_ALIAS = "wtf.iskra.secrets"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val VERSION: Byte = 1
        private val SAFE_KEY = Regex("[A-Za-z0-9._-]+")
        private val random = SecureRandom()
    }
}
