package wtf.iskra.core.media

/**
 * Насколько сжимать фото и видео перед отправкой. Порт `MediaCompression`.
 *
 * Три шага, а не ползунок: человек выбирает между «чат грузится на плохой сети», «хватит для всего,
 * что смотрят на телефоне» и «почти оригинал», и каждый шаг — пара чисел, для картинок и для видео,
 * которые имеют смысл только вместе.
 */
enum class MediaCompression(
    /** Длинная сторона фото, в пикселях. Никогда не увеличивается: меньшее фото остаётся своего размера. */
    val maxPhotoSide: Int,
    /** Качество JPEG сжатого фото, 0…100. */
    val photoQuality: Int,
    /** Рамка сжатого видео: длинная и короткая стороны — вертикальное получает столько же пикселей. */
    val videoLong: Int,
    val videoShort: Int,
    /** Бит в секунду картинки — потолок, а не цель. */
    val videoBitRate: Int,
    val audioBitRate: Int,
    /** Кадров в секунду, не больше. Шестьдесят — только на самом лёгком шаге. */
    val maxFrameRate: Int,
) {
    STRONG(1280, 60, 960, 540, 1_200_000, 64_000, 30),
    BALANCED(2048, 75, 1280, 720, 2_500_000, 96_000, 30),
    LIGHT(4096, 85, 1920, 1080, 5_000_000, 128_000, 60),
    ;

    /** Имя для хранения — то же, что `rawValue` на iOS. */
    val storageName: String get() = name.lowercase()

    companion object {
        fun of(storageName: String?): MediaCompression? = entries.firstOrNull { it.storageName == storageName }
    }
}

/**
 * Что происходит с фото и видео по дороге наружу: теряют ли метаданные и сжимаются ли — и насколько.
 * Порт `MediaSettings`; умолчания — те же, что у переключателей на экране.
 */
data class MediaSettings(val stripsMetadata: Boolean, val compresses: Boolean, val compression: MediaCompression) {
    companion object {
        /** Да. Где снято фото — не то, что решают отправить, отправляя фото. */
        const val STRIPS_METADATA_BY_DEFAULT = true

        /**
         * Да, на среднем шаге: полные двенадцать мегапикселей телефонного фото — несколько мегабайт,
         * которых не видно на телефоне и за которые платят загрузка отправителя и каждого получателя.
         */
        const val COMPRESSES_BY_DEFAULT = true
        val COMPRESSION_BY_DEFAULT = MediaCompression.BALANCED

        val DEFAULT = MediaSettings(STRIPS_METADATA_BY_DEFAULT, COMPRESSES_BY_DEFAULT, COMPRESSION_BY_DEFAULT)

        /** Кружочек — что бы ни было в настройках: всегда без метаданных и не сжимается второй раз. */
        val VIDEO_NOTE = MediaSettings(stripsMetadata = true, compresses = false, compression = COMPRESSION_BY_DEFAULT)
    }
}
