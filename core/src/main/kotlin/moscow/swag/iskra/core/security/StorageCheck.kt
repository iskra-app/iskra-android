package moscow.swag.iskra.core.security

/**
 * Настоящий круг через хранилище секретов: записать, прочитать, сверить, удалить. Аналог
 * `Iskra --verify-storage` (docs/PROMPT.md, 5.3).
 *
 * На Android подпись не меняет доступ к Keystore, но устройства различаются — StrongBox, сломанные
 * Keystore у некоторых производителей, — и проверить можно только на самом телефоне. Проба и
 * (позже) экран зовут одну и ту же функцию.
 */
object StorageCheck {
    data class Result(val ok: Boolean, val summary: String)

    private const val PROBE_KEY = "storage-check"

    fun run(secrets: SecretStoring): Result {
        val value = SessionStore.randomSecret().encodeToByteArray()
        return try {
            secrets.write(PROBE_KEY, value)
            val back = secrets.read(PROBE_KEY)
            secrets.remove(PROBE_KEY)
            val key = (secrets as? KeystoreSecrets)?.describeKey() ?: secrets.javaClass.simpleName
            when {
                back == null -> Result(false, "записалось и не прочиталось ($key)")
                !back.contentEquals(value) -> Result(false, "прочиталось не то, что записано ($key)")
                secrets.read(PROBE_KEY) != null -> Result(false, "не удалилось ($key)")
                else -> Result(true, "круг через хранилище прошёл: $key")
            }
        } catch (error: Exception) {
            Result(false, "хранилище отказало: ${moscow.swag.iskra.core.support.Log.describe(error)}")
        }
    }
}
