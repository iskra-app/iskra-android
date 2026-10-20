package wtf.iskra.features.push

import wtf.iskra.core.rooms.RoomSummary

/**
 * Число непрочитанного «на иконке». Порт `AppBadge.swift`.
 *
 * Счётчика на иконке у Android почти нет — у лаунчеров точки, а число, если оно есть, лаунчер берёт из
 * `setNumber` уведомлений. Но арифметика та же, что на iPhone, и считает то же, что отмечает список:
 * непрочитанные сообщения и приглашения — они непрочитаны в том смысле, который важен, хотя в них ещё
 * никто ничего не сказал. Пространства не считаются: их число — сумма комнат, уже сосчитанных здесь.
 */
object AppBadge {
    fun count(rooms: List<RoomSummary>): Int = rooms.sumOf { room ->
        when {
            room.isSpace -> 0
            // Приглашение — один раз, что бы в нём ни было: одно ждущее приглашение — одно дело.
            room.isInvitation -> 1
            else -> room.unreadCount
        }
    }
}
