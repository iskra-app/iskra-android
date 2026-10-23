package wtf.iskra.design

/**
 * Шрифт интерфейса: Google Sans, Inter или системный. Только Android: на iOS шрифт — системный SF.
 *
 * Google Sans — по умолчанию, к остальному Material 3 от Google. Inter — шрифт, с которым приложение
 * жило до него и который ближе всего к SF на iPhone. Системный — тот, что выбран в телефоне (Roboto или
 * шрифт производителя), для того, кому чужой шрифт в одном приложении мешает.
 */
enum class AppFont(val storageName: String) {
    GOOGLE_SANS("googleSans"),
    INTER("inter"),
    SYSTEM("system"),
    ;

    companion object {
        val Default = GOOGLE_SANS

        fun fromStorage(name: String?): AppFont = entries.firstOrNull { it.storageName == name } ?: Default
    }
}
