package wtf.iskra.core.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.NotificationSettings
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import wtf.iskra.core.R
import wtf.iskra.core.support.Log

/**
 * Сколько говорить о чате: все сообщения, только упоминания или ничего. Порт `RoomAlerts`. Четвёртого
 * «как везде» нет: настройка чата — всегда явное правило на сервере.
 */
enum class RoomAlerts {
    ALL,
    MENTIONS,
    MUTED,
    ;

    internal val mode: RoomNotificationMode
        get() = when (this) {
            ALL -> RoomNotificationMode.ALL_MESSAGES
            MENTIONS -> RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
            MUTED -> RoomNotificationMode.MUTE
        }

    internal companion object {
        fun of(mode: RoomNotificationMode): RoomAlerts = when (mode) {
            RoomNotificationMode.ALL_MESSAGES -> ALL
            RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> MENTIONS
            RoomNotificationMode.MUTE -> MUTED
        }
    }
}

/** Какое правило не изменилось — словами для человека. Порт `NotificationRulesFailure`. */
enum class NotificationRulesFailure(val explanation: Int) {
    DIRECT_DEFAULT(R.string.notification_rules_direct_failed),
    GROUP_DEFAULT(R.string.notification_rules_group_failed),
    MENTION(R.string.notification_rules_mention_failed),
    ROOM_MENTION(R.string.notification_rules_room_mention_failed),
    INVITATION(R.string.notification_rules_invitation_failed),
    CALL(R.string.notification_rules_call_failed),
}

/** Правила уведомлений аккаунта, как их видит экран. До загрузки — умолчания Matrix, но экран их не показывает. */
data class NotificationRulesState(
    val isLoaded: Boolean = false,
    /** Не прочиталось ничего — так выглядит сервер, который не отвечает, и экран говорит это, а не показывает умолчания. */
    val didFailToLoad: Boolean = false,
    val directChats: RoomAlerts = RoomAlerts.ALL,
    val groupChats: RoomAlerts = RoomAlerts.ALL,
    val mentionsMe: Boolean = true,
    val roomMentions: Boolean = true,
    val invitations: Boolean = true,
    val calls: Boolean = true,
    val failure: NotificationRulesFailure? = null,
)

/**
 * Узкая дверь к push rules SDK — ровно то, что нужно стору. Отдельно, чтобы правила отката проверялись
 * на JVM (`NotificationRulesStoreTest`), а не на сервере.
 */
internal interface NotificationRules {
    suspend fun defaultMode(isEncrypted: Boolean, isOneToOne: Boolean): RoomAlerts
    suspend fun setDefaultMode(isEncrypted: Boolean, isOneToOne: Boolean, alerts: RoomAlerts)
    suspend fun userMention(): Boolean
    suspend fun setUserMention(enabled: Boolean)
    suspend fun roomMention(): Boolean
    suspend fun setRoomMention(enabled: Boolean)
    suspend fun inviteForMe(): Boolean
    suspend fun setInviteForMe(enabled: Boolean)
    suspend fun call(): Boolean
    suspend fun setCall(enabled: Boolean)
}

private class SdkNotificationRules(private val settings: NotificationSettings) : NotificationRules {
    override suspend fun defaultMode(isEncrypted: Boolean, isOneToOne: Boolean) =
        RoomAlerts.of(settings.getDefaultRoomNotificationMode(isEncrypted, isOneToOne))

    override suspend fun setDefaultMode(isEncrypted: Boolean, isOneToOne: Boolean, alerts: RoomAlerts) =
        settings.setDefaultRoomNotificationMode(isEncrypted, isOneToOne, alerts.mode)

    override suspend fun userMention() = settings.isUserMentionEnabled()
    override suspend fun setUserMention(enabled: Boolean) = settings.setUserMentionEnabled(enabled)
    override suspend fun roomMention() = settings.isRoomMentionEnabled()
    override suspend fun setRoomMention(enabled: Boolean) = settings.setRoomMentionEnabled(enabled)
    override suspend fun inviteForMe() = settings.isInviteForMeEnabled()
    override suspend fun setInviteForMe(enabled: Boolean) = settings.setInviteForMeEnabled(enabled)
    override suspend fun call() = settings.isCallEnabled()
    override suspend fun setCall(enabled: Boolean) = settings.setCallEnabled(enabled)
}

/**
 * Правила уведомлений аккаунта — push rules **на сервере**: действуют на всех устройствах, а настройка
 * отдельного чата всегда главнее (docs/PROMPT.md, 10.6). Порт `NotificationRulesStore.swift`.
 *
 * Переключатель двигается сразу, запись идёт за ним; не записалось — возвращается и говорит, что не
 * вышло ([NotificationRulesState.failure]).
 */
class NotificationRulesStore internal constructor(private val source: suspend () -> NotificationRules) {
    internal constructor(client: Client) : this({ SdkNotificationRules(client.getNotificationSettings()) })

    private val mutableState = MutableStateFlow(NotificationRulesState())
    val state: StateFlow<NotificationRulesState> = mutableState.asStateFlow()

    /** Один запрос `NotificationSettings` на всех: экран открывают, и каждый переключатель спросил бы свой. */
    private val fetching = Mutex()
    private var rules: NotificationRules? = null

    private suspend fun rules(): NotificationRules = fetching.withLock { rules ?: source().also { rules = it } }

    /** Перечитать — при каждом открытии экрана: правила меняют и с других устройств. */
    suspend fun load() {
        val rules = attempt { rules() } ?: return mutableState.update { it.copy(isLoaded = true, didFailToLoad = true) }
        // Зашифрованная половина: приватный чат Искры — зашифрованный, а незашифрованная пара существует,
        // чтобы её *писать* (см. [setDefault]), а не спрашивать.
        val direct = attempt { rules.defaultMode(isEncrypted = true, isOneToOne = true) }
        val group = attempt { rules.defaultMode(isEncrypted = true, isOneToOne = false) }
        // Четыре вызова, и каждый может не ответить сам по себе. Прочитанные — правда об аккаунте.
        val mentionsMe = attempt { rules.userMention() }
        val roomMentions = attempt { rules.roomMention() }
        val invitations = attempt { rules.inviteForMe() }
        val calls = attempt { rules.call() }
        mutableState.update {
            it.copy(
                isLoaded = true,
                didFailToLoad = mentionsMe == null && roomMentions == null && invitations == null && calls == null,
                directChats = direct ?: it.directChats,
                groupChats = group ?: it.groupChats,
                mentionsMe = mentionsMe ?: it.mentionsMe,
                roomMentions = roomMentions ?: it.roomMentions,
                invitations = invitations ?: it.invitations,
                calls = calls ?: it.calls,
            )
        }
    }

    /**
     * Умолчание для личных или групп — в **обе** половины пары, зашифрованную и нет: иначе правило
     * действовало бы на одни чаты и молча не действовало на другие.
     */
    suspend fun setDefault(choice: RoomAlerts, isOneToOne: Boolean) {
        val previous = mutableState.value.let { if (isOneToOne) it.directChats else it.groupChats }
        if (choice == previous) return
        setAlerts(choice, isOneToOne)
        try {
            val rules = rules()
            for (encrypted in listOf(true, false)) rules.setDefaultMode(encrypted, isOneToOne, choice)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "the default notification rule would not stick: ${Log.describe(error)}")
            mutableState.update { state ->
                val current = if (isOneToOne) state.directChats else state.groupChats
                val rolled = if (current != choice) state else if (isOneToOne) state.copy(directChats = previous) else state.copy(groupChats = previous)
                rolled.copy(failure = if (isOneToOne) NotificationRulesFailure.DIRECT_DEFAULT else NotificationRulesFailure.GROUP_DEFAULT)
            }
        }
    }

    suspend fun setMentionsMe(enabled: Boolean) =
        flip(Field.MENTIONS_ME, enabled, NotificationRulesFailure.MENTION) { it.setUserMention(enabled) }

    suspend fun setRoomMentions(enabled: Boolean) =
        flip(Field.ROOM_MENTIONS, enabled, NotificationRulesFailure.ROOM_MENTION) { it.setRoomMention(enabled) }

    suspend fun setInvitations(enabled: Boolean) =
        flip(Field.INVITATIONS, enabled, NotificationRulesFailure.INVITATION) { it.setInviteForMe(enabled) }

    suspend fun setCalls(enabled: Boolean) =
        flip(Field.CALLS, enabled, NotificationRulesFailure.CALL) { it.setCall(enabled) }

    /** Человек прочитал, что не вышло. */
    fun dismissFailure() = mutableState.update { it.copy(failure = null) }

    private enum class Field { MENTIONS_ME, ROOM_MENTIONS, INVITATIONS, CALLS }

    private fun NotificationRulesState.get(field: Field) = when (field) {
        Field.MENTIONS_ME -> mentionsMe
        Field.ROOM_MENTIONS -> roomMentions
        Field.INVITATIONS -> invitations
        Field.CALLS -> calls
    }

    private fun NotificationRulesState.with(field: Field, value: Boolean) = when (field) {
        Field.MENTIONS_ME -> copy(mentionsMe = value)
        Field.ROOM_MENTIONS -> copy(roomMentions = value)
        Field.INVITATIONS -> copy(invitations = value)
        Field.CALLS -> copy(calls = value)
    }

    private suspend fun flip(field: Field, enabled: Boolean, failure: NotificationRulesFailure, write: suspend (NotificationRules) -> Unit) {
        val previous = mutableState.value.get(field)
        if (previous == enabled) return
        mutableState.update { it.with(field, enabled) }
        try {
            write(rules())
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.PUSH, "a notification rule would not stick: ${Log.describe(error)}")
            // Только если с тех пор его никто не двигал: второе нажатие, пока шло первое, владеет
            // переключателем, и вернуть старое значение первого — отменить то, что человек сделал после.
            mutableState.update { state ->
                val rolled = if (state.get(field) == enabled) state.with(field, previous) else state
                rolled.copy(failure = failure)
            }
        }
    }

    private fun setAlerts(choice: RoomAlerts, isOneToOne: Boolean) =
        mutableState.update { if (isOneToOne) it.copy(directChats = choice) else it.copy(groupChats = choice) }

    private suspend fun <T> attempt(read: suspend () -> T): T? = try {
        read()
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        null
    }
}
