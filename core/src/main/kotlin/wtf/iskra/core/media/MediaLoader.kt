package wtf.iskra.core.media

import wtf.iskra.core.support.withTimeLimit
import wtf.iskra.core.timeline.Attachment
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import java.io.File
import kotlin.time.Duration.Companion.minutes
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

    /**
     * Файл целиком — на диск, в `directory`, под именем отправителя: его будет читать *другое*
     * приложение, и оно читает после того, как эта функция вернулась. Открытый текст на диске, и
     * убирает его тот, кто дал папку (`FileOpener`), а не SDK: SDK удалил бы его из-под читателя.
     */
    suspend fun file(attachment: Attachment, directory: File): File = withTimeLimit(FILE_LIMIT) {
        directory.mkdirs()
        val target = File(directory, safeName(attachment.filename))
        val handle = client.getMediaFile(attachment.source, attachment.filename, attachment.mimeType ?: OCTET_STREAM, true, directory.path)
        try {
            if (!handle.persist(target.path)) File(handle.path()).copyTo(target, overwrite = true)
        } finally {
            handle.destroy()
        }
        target
    }

    override fun equals(other: Any?) = other is MediaLoader && other.client === client

    override fun hashCode() = System.identityHashCode(client)

    private companion object {
        val LIMIT = 30.seconds

        /** Файл может быть большим, а сеть — медленной. */
        val FILE_LIMIT = 10.minutes

        const val OCTET_STREAM = "application/octet-stream"
        const val FALLBACK_NAME = "file"

        /** Имя от отправителя — не путь: без каталогов и точек в начале, и не пустое. */
        fun safeName(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\').trimStart('.').take(MAX_NAME).ifBlank { FALLBACK_NAME }

        const val MAX_NAME = 120
    }
}
