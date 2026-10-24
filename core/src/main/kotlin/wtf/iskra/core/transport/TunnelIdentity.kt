package wtf.iskra.core.transport

import wtf.iskra.core.security.SecretStoring
import wtf.iskra.tunnel.generateSecretKey
import wtf.iskra.tunnel.npub
import wtf.iskra.tunnel.publicKeyHex

/**
 * Ключ nostr этой установки — обратный адрес туннеля. Порт `TunnelIdentity.swift`.
 *
 * Лежит в Keystore, и в **своём** каталоге секретов (`AppEnvironment.transportSecretsDir`), — это
 * единственное решение здесь, которое стоит объяснять. Секреты сеансов уходят при выходе
 * (`SessionStore.remove`), и ключ рядом с ними пересоздался бы при следующем запуске, а тот, кто
 * держит закрытый мост (`ALLOWED_PUBKEYS`), должен был бы добавить новый, прежде чем человек снова
 * войдёт, — через мост, до которого уже не достучаться. Адрес моста и relay'и переживают выход в
 * [TunnelSettings], и ключ переживает его вместе с ними.
 *
 * Это не личность в социальном смысле: на него никто не подписан, он ничего не публикует, и
 * существует только затем, чтобы ответ на запрос можно было адресовать устройству, которое спросило.
 */
class TunnelIdentity(private val secrets: SecretStoring) {
    /** Секретный ключ, 32 байта; созданный при первом вопросе. */
    @Synchronized
    fun secretKey(): ByteArray {
        secrets.read(KEY)?.takeIf { it.size == 32 && runCatching { publicKeyHex(it) }.isSuccess }?.let { return it }
        val fresh = generateSecretKey()
        secrets.write(KEY, fresh)
        return fresh
    }

    /** Открытый ключ, hex. */
    fun publicKeyHex(): String = publicKeyHex(secretKey())

    /** Тот же ключ, как его пишут люди: `npub1…`. Его добавляют в `ALLOWED_PUBKEYS` закрытого моста. */
    fun npub(): String = npub(publicKeyHex())

    private companion object {
        const val KEY = "nostr-secret"
    }
}
