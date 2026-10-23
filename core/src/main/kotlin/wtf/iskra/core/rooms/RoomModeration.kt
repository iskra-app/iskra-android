package wtf.iskra.core.rooms

/**
 * Сколько веса человек несёт в комнате *с точки зрения правил* — вопрос не тот же, что «какой значок он носит».
 * Роль — три слова для читающего; это число, которое сервер сравнит, решая, можно ли выгнать. Два администратора оба
 * читаются «Администратор» и не могут тронуть друг друга, а у создателя комнаты версии 12 уровень — не число вовсе, а
 * бесконечность, и никакое повышение до него не доходит. Поэтому [Unlimited] выше любого [Level] — и вот почему это
 * тип, а не `Int`. Порт `Authority`.
 */
sealed interface Authority : Comparable<Authority> {
    data class Level(val value: Long) : Authority
    data object Unlimited : Authority

    override fun compareTo(other: Authority): Int = when {
        this is Unlimited && other is Unlimited -> 0
        this is Unlimited -> 1
        other is Unlimited -> -1
        else -> (this as Level).value.compareTo((other as Level).value)
    }
}

/**
 * Кто на что может влиять в комнате. Вся логика модерации приложения — здесь, отдельно от стора, потому что только так
 * её можно проверить комнатой, которой в тестах не построить. Порт `canManage`, `roles(grantableBy:)`, `isMatrixID`.
 */
internal object RoomModeration {
    /**
     * Может ли этот аккаунт вообще действовать над тем человеком. Два правила, оба — сервера: нельзя убрать себя,
     * «выгнав» самого себя (уйти — другое действие, другая кнопка), и нельзя тронуть никого на своём уровне или выше.
     * Поэтому два администратора равны и ни один не снимет другого, а создатель выше обоих навсегда.
     */
    fun canManage(memberId: String, memberAuthority: Authority, ownUserId: String, own: Authority): Boolean =
        memberId != ownUserId && memberAuthority < own

    /**
     * Роли, которые можно отдать другому: до *включительно* своей — Matrix позволяет поднять до своего уровня и не
     * даёт перешагнуть. Повышение до того, что сам выдать не можешь, — пункт меню, который отказал бы на сервере,
     * поэтому его не предлагают.
     */
    fun rolesGrantableBy(own: Authority, levelOf: (RoomMemberSummary.Role) -> Authority): List<RoomMemberSummary.Role> =
        RoomMemberSummary.Role.entries.filter { levelOf(it) <= own }

    /** «@boris:matrix.org» и ничего другого. Проверяется здесь, а не сервером: на кривой ID он отвечает так же, как на неизвестный. */
    fun isMatrixId(text: String): Boolean {
        val candidate = text.trim()
        if (!candidate.startsWith("@")) return false
        val parts = candidate.drop(1).split(':', limit = 2)
        return parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() && ' ' !in parts[1]
    }
}

/**
 * Кто может прочесть сказанное в комнате до того, как пришёл, — `m.room.history_visibility` словами человека. Порт
 * `HistoryAccess`.
 *
 * Telegram спрашивает это про группу «да или нет» («история для новых участников»), Matrix — четырьмя ответами. Три из
 * них — одно «да» в разные моменты (всё; с приглашения; со вхождения), и предлагаются все три: это разные обещания тем,
 * кто уже говорит. Четвёртый, [WORLD_READABLE], — совсем другое: читать может кто угодно, не входя; он только для комнаты,
 * в которую и так может войти любой, где не обещает ничего сверх открытой двери.
 *
 * Обещание держит *сервер*, и только за то, что он может прочесть. В зашифрованной комнате вошедший получает события, а не
 * ключи к ним, и «всё» там значит «всё, нечитаемое», пока кто-то не поделится ключами, — экран говорит это.
 */
enum class HistoryAccess {
    SHARED,
    INVITED,
    JOINED,
    WORLD_READABLE,
    ;

    companion object {
        /** Что предлагается: все четыре в открытой комнате, три первых в любой другой. */
        fun choices(isPublic: Boolean): List<HistoryAccess> = if (isPublic) entries else listOf(SHARED, INVITED, JOINED)
    }
}
