package wtf.iskra.core.media

import java.io.File
import java.io.RandomAccessFile

/**
 * Чист ли MP4, который вот-вот уйдёт, — как [MetadataCheck] для фото: подготовка *сказала*, что
 * выбросила метаданные, а это проверяет готовый файл.
 *
 * Метаданные видео живут в заголовке контейнера, не в кадрах: в `moov` (`udta` с `©xyz` и `loci`,
 * `meta` с ключами `com.apple.quicktime.location.ISO6709`, `…make`, `…model`) и в XMP — коробкой
 * `uuid` или `meta` на верхнем уровне. Поэтому читаются только эти коробки, а `mdat` — сотни
 * мегабайт кадров — перешагивается по размеру. Не разобрался, слишком большой заголовок — не чист:
 * ошибиться можно только в сторону «не отправлять».
 */
object Mp4MetadataCheck {
    fun isClean(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input -> headers(input)?.none(::carriesMetadata) ?: false }
    }.getOrDefault(false)

    /** Есть ли в байтах заголовка то, что говорит о месте, устройстве или XMP. */
    internal fun carriesMetadata(header: ByteArray): Boolean = MARKERS.any { indexOf(header, it) >= 0 }

    /**
     * Коробки верхнего уровня, в которых могут быть метаданные: `moov`, `meta`, `uuid`, `udta`.
     * Null — файл не разобрался или заголовок подозрительно велик.
     */
    private fun headers(input: RandomAccessFile): List<ByteArray>? {
        val found = mutableListOf<ByteArray>()
        val length = input.length()
        var at = 0L
        var total = 0L
        while (at + BOX_HEADER <= length) {
            input.seek(at)
            var size = input.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(TYPE_LENGTH).also(input::readFully).decodeToString()
            var header = BOX_HEADER.toLong()
            when (size) {
                0L -> size = length - at
                1L -> {
                    size = input.readLong()
                    header += Long.SIZE_BYTES
                }
            }
            if (size < header || at + size > length) return null
            if (type in WATCHED) {
                total += size
                if (total > MAX_HEADER_BYTES) return null
                input.seek(at)
                found += ByteArray(size.toInt()).also(input::readFully)
            }
            at += size
        }
        return if (at == length && found.any { it.size >= BOX_HEADER && it.copyOfRange(4, 8).decodeToString() == "moov" }) found else null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private const val BOX_HEADER = 8
    private const val TYPE_LENGTH = 4

    /** Заголовок обычного ролика — килобайты; десятки мегабайт — не заголовок, которому стоит верить. */
    private const val MAX_HEADER_BYTES = 32L * 1024 * 1024

    private val WATCHED = setOf("moov", "meta", "uuid", "udta")

    /** Что в заголовке выдаёт место, устройство или XMP. `©xyz` — с байтом 0xA9, как в файле. */
    private val MARKERS: List<ByteArray> = listOf(
        byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()),
        "loci".toByteArray(),
        "com.apple.quicktime.location".toByteArray(),
        "com.apple.quicktime.make".toByteArray(),
        "com.apple.quicktime.model".toByteArray(),
        "<x:xmpmeta".toByteArray(),
        "XMP_".toByteArray(),
    )
}
