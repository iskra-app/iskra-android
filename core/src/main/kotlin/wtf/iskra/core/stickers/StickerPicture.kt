package wtf.iskra.core.stickers

import android.graphics.BitmapFactory
import wtf.iskra.core.media.MetadataCheck
import wtf.iskra.core.media.PhotoPreparation

/** Картинка стикера, готовая уйти зашифрованной картинкой. Порт `StickerPicture` (половина «из пака»). */
class PreparedSticker(val bytes: ByteArray, val filename: String, val mimeType: String, val width: Int, val height: Int, val blurhash: String)

/**
 * Стикер из пака в зашифрованной комнате. `uploadMedia` не шифрует, а настоящий `m.sticker` показал бы
 * картинку серверу открытым текстом, поэтому такой стикер уходит *зашифрованной картинкой*, названной
 * `sticker.<расширение>` — так Искра рисует её стикером (`MessagePreview.isSticker`), а чужие клиенты —
 * картинкой: честная цена закрытости.
 *
 * Метаданные не убираются, а проверяются (`MetadataCheck`): картинка, что не чиста, не уходит вовсе.
 * Стикеры «Искорок» и обычных паков чисты; чем это не так, тем лучше отказ, чем GPS в чужом чате.
 */
object StickerPicture {
    /** Null — не картинка или не чисто. */
    fun prepare(bytes: ByteArray): PreparedSticker? {
        if (!MetadataCheck.isClean(bytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val extension = when {
            MetadataCheck.isPng(bytes) -> "png"
            MetadataCheck.isWebp(bytes) -> "webp"
            else -> "jpg"
        }
        val mimeType = bounds.outMimeType ?: return null
        return PreparedSticker(
            bytes = bytes,
            filename = "sticker.$extension",
            mimeType = mimeType,
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            blurhash = PhotoPreparation.blurhashOf(bytes, bounds),
        )
    }
}
