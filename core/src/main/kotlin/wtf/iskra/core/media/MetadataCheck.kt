package wtf.iskra.core.media

/**
 * Чистый ли файл, который сейчас уйдёт, — последняя проверка перед отправкой (docs/PROMPT.md, 8.3).
 *
 * Не «нет ли в нём GPS», а **белый список**: файл разбирается до структуры, и всё, чего нет в
 * списке, — грязь. Так ловится и то, о чём не подумали: в iOS «копия без метаданных» оставляла GPS
 * в PNG и дату съёмки в HEIC, и находилось это только чтением результата обратно. Здесь читается
 * тоже результат, и собственными руками: верить `ExifInterface`, что тега нет, — верить тому же
 * Android, что, возможно, его и записал.
 *
 * - JPEG: маркеры от SOI до EOI. Можно: таблицы (DQT, DHT, DRI), кадр (SOF*), скан (SOS и
 *   данные за ним с RST), APP0 «JFIF» и APP2 «ICC_PROFILE» (цветовой профиль — про цвет, не про
 *   человека). Нельзя: APP1 (EXIF и XMP), APP13 (IPTC), COM и всё остальное, включая хвост после EOI
 *   (туда телефоны дописывают MPF и карты глубины).
 * - PNG: чанки. Можно: картинка и её цвет. Нельзя: eXIf, tEXt, zTXt, iTXt, tIME, неизвестные и хвост
 *   после IEND.
 *
 * - WebP: чанки RIFF. Можно: картинка, прозрачность, анимация, цвет. Нельзя: `EXIF`, `XMP `, неизвестные,
 *   флаги EXIF/XMP в `VP8X` и хвост за объявленным размером.
 *
 * Чего не разобрать — не JPEG, не PNG и не WebP, или битое, — то не чистое.
 */
object MetadataCheck {
    fun isClean(bytes: ByteArray): Boolean = when {
        isJpeg(bytes) -> cleanJpeg(bytes)
        isPng(bytes) -> cleanPng(bytes)
        isWebp(bytes) -> cleanWebp(bytes)
        else -> false
    }

    fun isJpeg(bytes: ByteArray): Boolean = bytes.size >= 4 && u8(bytes, 0) == 0xFF && u8(bytes, 1) == SOI

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

    fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= WEBP_HEADER && startsWith(bytes, 0, bytes.size, RIFF) && startsWith(bytes, 8, bytes.size - 8, WEBP)

    private fun cleanJpeg(bytes: ByteArray): Boolean {
        var at = 2
        while (at < bytes.size) {
            if (u8(bytes, at) != 0xFF) return false
            // Заполнители: сколько угодно 0xFF перед маркером.
            while (at < bytes.size && u8(bytes, at) == 0xFF) at++
            if (at >= bytes.size) return false
            val marker = u8(bytes, at)
            at++
            when {
                marker == EOI -> return at == bytes.size
                marker in RST0..RST7 || marker == TEM -> continue
            }
            if (at + 2 > bytes.size) return false
            val length = (u8(bytes, at) shl 8) or u8(bytes, at + 1)
            if (length < 2 || at + length > bytes.size) return false
            if (!allowedJpegSegment(marker, bytes, at + 2, length - 2)) return false
            at += length
            if (marker == SOS) {
                // Данные скана: до первого маркера, который не «FF 00» (экранированный байт) и не RST.
                while (at < bytes.size) {
                    if (u8(bytes, at) == 0xFF && at + 1 < bytes.size) {
                        val next = u8(bytes, at + 1)
                        if (next != 0x00 && next !in RST0..RST7 && next != 0xFF) break
                    }
                    at++
                }
            }
        }
        // Дошли до конца без EOI — обрезанный файл.
        return false
    }

    private fun allowedJpegSegment(marker: Int, bytes: ByteArray, start: Int, size: Int): Boolean = when (marker) {
        DQT, DHT, DRI, SOS -> true
        APP0 -> startsWith(bytes, start, size, JFIF)
        APP2 -> startsWith(bytes, start, size, ICC_PROFILE)
        // SOF0…SOF15, кроме тех номеров, что заняты DHT (C4), JPG (C8) и DAC (CC).
        in 0xC0..0xCF -> marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        else -> false
    }

    private fun cleanPng(bytes: ByteArray): Boolean {
        var at = PNG_SIGNATURE.size
        while (at + CHUNK_HEADER <= bytes.size) {
            val length = ((u8(bytes, at) shl 24) or (u8(bytes, at + 1) shl 16) or (u8(bytes, at + 2) shl 8) or u8(bytes, at + 3)).toLong()
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            val end = at + CHUNK_HEADER + length + CRC
            if (length < 0 || end > bytes.size) return false
            if (type !in PNG_ALLOWED) return false
            at = end.toInt()
            if (type == "IEND") return at == bytes.size
        }
        return false
    }

    /**
     * WebP — контейнер RIFF: `RIFF`, длина, `WEBP`, дальше чанки (четыре буквы, длина, данные до чётного
     * байта). Размер в заголовке обязан сходиться с файлом: дописанное за ним — хвост. EXIF и XMP —
     * чанки, но в `VP8X` о них ещё и флаги: файл, объявивший их, не чист, даже если чанка не нашлось.
     * Кадры анимации (`ANMF`) несут свои чанки внутри, и их проверяют тем же списком.
     */
    private fun cleanWebp(bytes: ByteArray): Boolean {
        val declared = u32le(bytes, 4) + 8
        if (declared != bytes.size.toLong() || bytes.size % 2 != 0) return false
        return cleanWebpChunks(bytes, WEBP_HEADER, bytes.size)
    }

    private fun cleanWebpChunks(bytes: ByteArray, from: Int, to: Int): Boolean {
        var at = from
        while (at < to) {
            if (at + CHUNK_HEADER > to) return false
            val type = String(bytes, at, 4, Charsets.ISO_8859_1)
            val length = u32le(bytes, at + 4)
            val body = at + CHUNK_HEADER
            val end = body + length + (length and 1)
            if (end > to || type !in WEBP_ALLOWED) return false
            if (type == "VP8X" && (length < 10 || u8(bytes, body) and WEBP_EXIF_OR_XMP != 0)) return false
            if (type == "ANMF" && (length < ANMF_HEADER || !cleanWebpChunks(bytes, body + ANMF_HEADER, (body + length).toInt()))) return false
            at = end.toInt()
        }
        return at == to
    }

    private fun u32le(bytes: ByteArray, at: Int): Long =
        (u8(bytes, at).toLong()) or (u8(bytes, at + 1).toLong() shl 8) or (u8(bytes, at + 2).toLong() shl 16) or (u8(bytes, at + 3).toLong() shl 24)

    private fun startsWith(bytes: ByteArray, start: Int, size: Int, prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { bytes[start + it] == prefix[it] }

    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 0xFF

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val DQT = 0xDB
    private const val DHT = 0xC4
    private const val DRI = 0xDD
    private const val APP0 = 0xE0
    private const val APP2 = 0xE2
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
    private const val TEM = 0x01

    private val JFIF = byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0)
    private val ICC_PROFILE = "ICC_PROFILE".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private const val CHUNK_HEADER = 8
    private const val CRC = 4

    private const val WEBP_HEADER = 12
    private const val ANMF_HEADER = 16
    private const val WEBP_EXIF_OR_XMP = 0x0C
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    /** Картинка, её прозрачность, анимация и цвет. Ни `EXIF`, ни `XMP `. */
    private val WEBP_ALLOWED = setOf("VP8 ", "VP8L", "VP8X", "ALPH", "ANIM", "ANMF", "ICCP")

    /** Картинка и её цвет. Ни слова о том, кто, где и когда. */
    private val PNG_ALLOWED = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT", "bKGD", "pHYs")
}
