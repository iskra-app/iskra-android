package wtf.iskra.core.support

/** Чьи чаты показывать там, где аккаунтов несколько («Поделиться», виджет): открытый, все или конкретный по ID (`ChatDirectory.ShareAccount`). */
object AccountChoice {
    const val ACTIVE = "active"
    const val ALL = "all"

    /** Попадает ли в выбор аккаунт [accountId], когда на экране — [active]. */
    fun includes(choice: String, accountId: String, active: String?): Boolean = when (choice) {
        ACTIVE -> accountId == active
        ALL -> true
        else -> accountId == choice
    }
}
