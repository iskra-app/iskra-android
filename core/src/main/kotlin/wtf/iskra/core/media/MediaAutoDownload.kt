package wtf.iskra.core.media

/**
 * Скачивать ли фото и видео в ленте само, когда они показались на экране — порт `MediaAutoDownload`. Аватарки и стикеры
 * качаются всегда: они малы и без них приложение выглядит сломанным.
 */
enum class MediaAutoDownload(val storageName: String) {
    ALWAYS("always"),
    WIFI("wifi"),
    NEVER("never"),
    ;

    /** [expensive] — сеть платная (мобильные данные, роуминг, режим экономии трафика). */
    fun allows(expensive: Boolean): Boolean = when (this) {
        ALWAYS -> true
        WIFI -> !expensive
        NEVER -> false
    }

    companion object {
        val DEFAULT = ALWAYS

        fun of(name: String?): MediaAutoDownload = entries.firstOrNull { it.storageName == name } ?: DEFAULT
    }
}
