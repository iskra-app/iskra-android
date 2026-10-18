package wtf.iskra.design

/**
 * Светлая, тёмная или как в системе. Порт `AppearanceMode.swift`.
 *
 * Системная — по умолчанию и должна оставаться обычным случаем. Это для того, кому «поменять для
 * всего телефона» — не ответ.
 */
enum class AppearanceMode(val storageName: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    /** Тёмное ли оформление при системном [systemIsDark]. `SYSTEM` — это отсутствие своего мнения. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val Default = SYSTEM
        const val STORAGE_KEY = "appearance.mode"

        fun fromStorage(name: String?): AppearanceMode = entries.firstOrNull { it.storageName == name } ?: Default
    }
}
