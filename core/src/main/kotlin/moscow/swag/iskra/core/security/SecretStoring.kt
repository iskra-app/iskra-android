package moscow.swag.iskra.core.security

/**
 * Где лежат секреты человека. Порт `SecretStoring.swift`.
 *
 * В приложении это всегда [KeystoreSecrets], и никакая настройка не делает его чем-то другим.
 * Интерфейс есть для тестов на JVM, где Android Keystore нет, и для сервера входа и демо, чьи
 * токены относятся к серверу, который исчезнет вместе с процессом ([InMemorySecrets]).
 */
interface SecretStoring {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun remove(key: String)
    fun removeAll()
}

/** Ошибка хранилища секретов: Keystore не открылся, запись не расшифровалась, диск не записал. */
class SecretStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Секреты, которые живут ровно столько, сколько объект. Порт `InMemorySecrets.swift`: для тестов и
 * для сеанса против встроенного сервера, чьи токены не должны лечь туда, где лежат токены настоящего
 * аккаунта.
 */
class InMemorySecrets : SecretStoring {
    private val items = mutableMapOf<String, ByteArray>()

    @Synchronized override fun read(key: String): ByteArray? = items[key]?.copyOf()
    @Synchronized override fun write(key: String, value: ByteArray) { items[key] = value.copyOf() }
    @Synchronized override fun remove(key: String) { items.remove(key) }
    @Synchronized override fun removeAll() = items.clear()
}
