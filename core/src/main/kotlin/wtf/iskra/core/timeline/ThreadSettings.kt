package wtf.iskra.core.timeline

import wtf.iskra.core.rooms.RoomKind

/**
 * Где комната держит ответы в тредах — «Настройки → Чаты». Порт `ThreadSettings.swift`. Два вопроса
 * порознь:
 *
 * - **в каких комнатах** треды вообще есть ([shown]): всегда, никогда или только больше [LARGE_ROOM]
 *   человек — по умолчанию. В большой комнате тред не даёт одному обсуждению утопить остальные; среди
 *   дюжины друзей он чаще прячет ответ, который хотели бы видеть;
 * - **уходят ли ответы из ленты комнаты** ([hidesReplies]). По умолчанию нет: тред — строка под
 *   сообщением, открывающая обсуждение, а ответы *ещё и* там, где написаны, — кто треды не открывает,
 *   ничего не пропустит. Включено — ответы только в треде, как комментарии в Element и Telegram.
 *
 * В личных чатах тредов нет никогда: между двумя людьми тред — вторая переписка с тем же человеком.
 * Читается, когда лента комнаты открывается: фокус открытой ленты не меняется.
 */
data class ThreadSettings(
    val shown: Shown = SHOWN_BY_DEFAULT,
    val hidesReplies: Boolean = HIDES_REPLIES_BY_DEFAULT,
) {
    enum class Shown(val storageName: String) {
        ALWAYS("always"),
        IN_LARGE_ROOMS("inLargeRooms"),
        NEVER("never"),
        ;

        companion object {
            fun of(storageName: String?): Shown? = entries.firstOrNull { it.storageName == storageName }
        }
    }

    /** Есть ли треды в комнате такого вида и размера. */
    fun usesThreads(kind: RoomKind, memberCount: Int): Boolean {
        if (kind == RoomKind.DIRECT) return false
        return when (shown) {
            Shown.ALWAYS -> true
            Shown.IN_LARGE_ROOMS -> memberCount > LARGE_ROOM
            Shown.NEVER -> false
        }
    }

    companion object {
        val SHOWN_BY_DEFAULT = Shown.IN_LARGE_ROOMS
        const val HIDES_REPLIES_BY_DEFAULT = false

        /** «Большая комната»: больше стольких человек. */
        const val LARGE_ROOM = 100
    }
}
