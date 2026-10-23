package wtf.iskra.core.rooms

import org.matrix.rustcomponents.sdk.RoomPowerLevelsValues
import uniffi.matrix_sdk.RoomPowerLevelChanges

/**
 * Одно, что комната может разрешить или удержать, — по ролям. Порт `RoomRule`.
 *
 * Те, что `Room.applyPowerLevelChanges` умеет двигать. У Matrix порог есть на каждый тип события, и экран со всеми был бы
 * снова моделью данных; здесь — восемь, о которых у того, кто ведёт группу, есть мнение.
 */
enum class RoomRule {
    SEND_MESSAGES,
    INVITE,
    REMOVE,
    BAN,
    DELETE_OTHERS,
    RENAME,
    CHANGE_TOPIC,
    CHANGE_PICTURE,
    ;

    /** Порог, прочитанный из значений комнаты. */
    internal fun level(values: RoomPowerLevelsValues): Long = when (this) {
        SEND_MESSAGES -> values.eventsDefault
        INVITE -> values.invite
        REMOVE -> values.kick
        BAN -> values.ban
        DELETE_OTHERS -> values.redact
        RENAME -> values.roomName
        CHANGE_TOPIC -> values.roomTopic
        CHANGE_PICTURE -> values.roomAvatar
    }

    /** Изменение, что двигает этот порог и ничего больше. */
    internal fun change(level: Long): RoomPowerLevelChanges = when (this) {
        SEND_MESSAGES -> RoomPowerLevelChanges(eventsDefault = level)
        INVITE -> RoomPowerLevelChanges(invite = level)
        REMOVE -> RoomPowerLevelChanges(kick = level)
        BAN -> RoomPowerLevelChanges(ban = level)
        DELETE_OTHERS -> RoomPowerLevelChanges(redact = level)
        RENAME -> RoomPowerLevelChanges(roomName = level)
        CHANGE_TOPIC -> RoomPowerLevelChanges(roomTopic = level)
        CHANGE_PICTURE -> RoomPowerLevelChanges(roomAvatar = level)
    }
}

/**
 * Кто что может в комнате: каждое [RoomRule] и самая низкая роль, которой можно. Порог — число, роль — ступень (см.
 * [RoomModeration.roleClearing]).
 */
data class RoomRules(val levels: Map<RoomRule, Long>) {
    internal constructor(values: RoomPowerLevelsValues) : this(RoomRule.entries.associateWith { it.level(values) })

    /** Самая низкая роль, которой это можно. */
    internal fun role(rule: RoomRule, levelOf: (RoomMemberSummary.Role) -> Authority): RoomMemberSummary.Role =
        RoomModeration.roleClearing(levels[rule] ?: 0, levelOf)
}
