package moscow.swag.iskra.core.media

import moscow.swag.iskra.core.support.withTimeLimit
import moscow.swag.iskra.core.timeline.Attachment
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import kotlin.time.Duration.Companion.seconds

/**
 * Достаёт байты медиа по запросу. Порт `MediaLoader.swift`; пока — то, что нужно списку чатов.
 *
 * В зашифрованной комнате SDK расшифровывает файл при загрузке, и пришедшее сюда — уже открытый
 * текст: оно отдаётся экрану и никогда не пишется в свой кэш. Кэш SDK — тот зашифрованный стор,
 * что собран при входе. Вложения (миниатюры, файлы, кружочки) придут с этапами 4 и 6.
 *
 * Два загрузчика над одним клиентом — один загрузчик: экраны сравнивают их, и равенство по клиенту
 * не даёт им перерисовываться на каждом тике синхронизации.
 */
class MediaLoader internal constructor(private val client: Client) {
    /**
     * Лицо человека или картинка комнаты по `mxc://`, что сервер отдаёт рядом с именем. Всегда
     * миниатюра: аватарка рисуется в 56 точек, а за ней может быть двухмегапиксельное фото. Через
     * ресайзер сервера без колебаний — аватарка публична по построению и живёт вне шифрования комнаты.
     */
    suspend fun avatar(url: String, size: Int): ByteArray = withTimeLimit(LIMIT) {
        val source = MediaSource.fromUrl(url)
        try {
            client.getMediaThumbnail(source, size.toLong().coerceAtLeast(1).toULong(), size.toLong().coerceAtLeast(1).toULong())
        } finally {
            source.destroy()
        }
    }

    /**
     * Миниатюра вложения — для ленты, чтобы прокрутка мимо видео не качала видео.
     *
     * Сначала кадр, загруженный *отправителем*: для видео в зашифрованной комнате это единственное, что
     * может сработать — `getMediaThumbnail` просит *сервер* уменьшить файл, а у сервера шифротекст.
     * Фото без своего кадра уменьшается обычным путём: SDK расшифровывает и уменьшает его на устройстве.
     */
    suspend fun thumbnail(attachment: Attachment, size: Int): ByteArray = withTimeLimit(LIMIT) {
        val side = size.toLong().coerceAtLeast(1).toULong()
        attachment.thumbnailSource?.let { return@withTimeLimit client.getMediaContent(it) }
        client.getMediaThumbnail(attachment.source, side, side)
    }

    /**
     * Медиа целиком — фото, которое открыли, а не лента. В зашифрованной комнате SDK расшифровывает
     * при загрузке: это открытый текст, и он отдаётся экрану, а не пишется в свой кэш.
     */
    suspend fun content(attachment: Attachment): ByteArray = withTimeLimit(LIMIT) { client.getMediaContent(attachment.source) }

    override fun equals(other: Any?) = other is MediaLoader && other.client === client

    override fun hashCode() = System.identityHashCode(client)

    private companion object {
        val LIMIT = 30.seconds
    }
}
