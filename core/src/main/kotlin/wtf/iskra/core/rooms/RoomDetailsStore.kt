package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.JoinRule
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.NotificationSettings
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.RoomHistoryVisibility
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RoomMember
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.UserPowerLevelUpdate
import org.matrix.rustcomponents.sdk.isRoomAliasFormatValid
import org.matrix.rustcomponents.sdk.roomAliasNameFromRoomDisplayName
import org.matrix.rustcomponents.sdk.suggestedPowerLevelForRole
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk.RoomMemberRole
import uniffi.ruma_events.StateEventType
import wtf.iskra.core.R
import wtf.iskra.core.notifications.RoomAlerts
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Участник, как его показывает «О чате». */
data class RoomMemberSummary(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val role: Role,
    /** Позван, но ещё не вошёл. */
    val isInvited: Boolean,
    /** Число за ролью: только сравнивается, никогда не показывается — см. [Authority]. */
    val authority: Authority = Authority.Level(0),
    /**
     * Кто-то ещё в комнате зовёт себя так же. Отображаемые имена в Matrix не уникальны и не проверены, так что «Борис» в
     * комнате может быть двумя людьми — мелкая и дешёвая подмена. SDK её замечает; список тогда показывает Matrix ID
     * рядом с именем, потому что ID — то, что никто не подделает.
     */
    val isNameAmbiguous: Boolean = false,
) {
    enum class Role { ADMINISTRATOR, MODERATOR, MEMBER }
}

/** Что не вышло на «О чате» — словами для человека. */
enum class RoomDetailsFailure(val message: Int) {
    LOAD(R.string.room_action_load_failed),
    ALERTS(R.string.room_action_alerts_failed),
    RENAME(R.string.room_action_rename_failed),
    TOPIC(R.string.room_action_topic_failed),
    PICTURE(R.string.room_action_picture_failed),
    INVITE(R.string.room_action_invite_failed),
    REMOVE(R.string.room_action_remove_failed),
    BAN(R.string.room_action_ban_failed),
    UNBAN(R.string.room_action_unban_failed),
    ROLE(R.string.room_action_role_failed),
    HISTORY(R.string.room_action_history_failed),
    ADDRESS(R.string.room_action_address_failed),
    ADDRESS_TAKEN(R.string.room_action_address_taken),
    DIRECTORY(R.string.room_action_directory_failed),
    LEAVE(R.string.room_action_leave_failed),
    RULES(R.string.room_action_rules_failed),
    VISIBILITY(R.string.room_action_visibility_failed),
}

/**
 * Что комната разрешает менять *этому* человеку. Matrix даёт три права порознь: модератор вполне может
 * переименовать чат и не может сменить картинку, — поэтому каждое поле правки показывается само по себе,
 * а не серым: серое поле обещало бы то, в чём сервер уже отказал.
 */
data class RoomPermissions(
    val canRename: Boolean = false,
    val canChangeTopic: Boolean = false,
    val canChangePicture: Boolean = false,
    /** Входит ли любой, кто нашёл комнату, — правила входа (`m.room.join_rules`). */
    val canChangeVisibility: Boolean = false,
    val canInvite: Boolean = false,
    val canRemove: Boolean = false,
    val canBan: Boolean = false,
    val canChangeRoles: Boolean = false,
    /** Кто читает сказанное до прихода — `m.room.history_visibility`. */
    val canChangeHistory: Boolean = false,
    /** Адрес комнаты `#имя:сервер` и список ли её сервер в каталоге. */
    val canChangeAddress: Boolean = false,
) {
    val canEditAnything: Boolean
        get() = canRename || canChangeTopic || canChangePicture || canChangeVisibility || canChangeRoles || canChangeHistory || canChangeAddress
}

data class RoomDetailsState(
    val isLoaded: Boolean = false,
    val name: String = "",
    /** Имя от сервера — подсказка «Вернуть …» под прозвищем. */
    val serverName: String = "",
    val topic: String? = null,
    val avatarUrl: String? = null,
    val avatarSeed: String? = null,
    val faces: List<RoomFace> = emptyList(),
    val kind: RoomKind = RoomKind.GROUP,
    val privacy: PrivacyStatus = PrivacyStatus.UNKNOWN,
    val memberCount: Int = 0,
    val members: List<RoomMemberSummary> = emptyList(),
    val isLoadingMembers: Boolean = true,
    /** Сколько говорить об этом чате; null — ещё не прочитали. */
    val alerts: RoomAlerts? = null,
    /** Прозвище на этом устройстве (только личный чат). */
    val localName: String? = null,
    val permissions: RoomPermissions = RoomPermissions(),
    /** Кто что может по ролям — таблица за [permissions], для экрана, что её меняет. Null, пока не прочитаны power levels. */
    val rules: RoomRules? = null,
    /** Заблокированные — для тех, кто может их вернуть ([RoomDetailsStore.unban]). */
    val banned: List<RoomMemberSummary> = emptyList(),
    /** Входит ли любой, кто нашёл комнату, или по приглашению. Читается из правил входа; `false`, пока их нет. */
    val isPublic: Boolean = false,
    /** Кто читает прошлое. */
    val history: HistoryAccess = HistoryAccess.SHARED,
    /** Опубликованный адрес `#имя:сервер`, если есть, — из него сделана ссылка. */
    val canonicalAlias: String? = null,
    /** Есть ли комната в каталоге сервера; null — не спрашивали: знает только сервер, и спрашивает только тот, кто меняет. */
    val isInDirectory: Boolean? = null,
    /** Картинка уходит на сервер — лицо на экране ещё старое. */
    val isUploadingPicture: Boolean = false,
    val failure: RoomDetailsFailure? = null,
) {
    val isDirect: Boolean get() = kind == RoomKind.DIRECT

    /** Имя адресного имени без `#` и сервера — то, что показывает поле. */
    val addressName: String get() = canonicalAlias?.drop(1)?.substringBefore(':').orEmpty()
}

/**
 * «О чате» (docs/PROMPT.md, 12.8). Порт части `RoomDetailsStore.swift`: кто в чате, как он зовётся на
 * этом устройстве, сколько о нём говорить, и имя, описание и картинка — для тех, кому комната их
 * менять разрешает. Роли, бан, приглашения, адрес и история — дальше по этапу 8.
 *
 * Имя и картинка держатся подпиской на комнату, как шапка переписки (`TimelineStore`): переименовали с
 * другого устройства, пока экран открыт, — экран показывает новое, а не снимок.
 */
class RoomDetailsStore internal constructor(
    private val room: Room,
    private val client: Client,
    val roomId: String,
    private val ownUserId: String,
    private val strings: Strings,
    private val scope: CoroutineScope,
    private val localNames: LocalNames.Account?,
) {
    private val mutableState = MutableStateFlow(RoomDetailsState())
    val state: StateFlow<RoomDetailsState> = mutableState.asStateFlow()

    private var infoHandle: TaskHandle? = null
    private var localNamesJob: Job? = null
    private var stopped = false

    private val fetching = Mutex()
    private var notifications: NotificationSettings? = null

    /** Вес этого аккаунта в комнате — от него зависит, кого можно трогать. */
    private var ownAuthority: Authority = Authority.Level(0)

    /** Прочие адреса комнаты: смена главного не должна их ронять. */
    private var alternativeAliases: List<String> = emptyList()

    /**
     * Правила, которые мы только что поставили и о которых кэш комнаты ещё не знает: уровень и когда поставлен. Снимок
     * комнаты, пришедший раньше синхронизации с нашей переменой, несёт *старое* число и стёр бы поставленное с экрана на
     * глазах; поэтому пока снимок не согласится (или не пройдёт [PENDING_RULE_MS]), поверх него лежит поставленное.
     */
    private val pendingRules = HashMap<RoomRule, Pair<Long, Long>>()

    /** Какой прогон: [stop] увеличивает, и пережившее await сверяет. */
    private var generation = 0

    /** Явным вызовом с экрана, а не побочным эффектом: комнату, которую не открыть, видно сразу. */
    suspend fun load() {
        val info = runCatching { withTimeLimit(LIMIT) { room.roomInfo() } }.getOrNull()
        if (stopped) {
            info?.destroy()
            return
        }
        if (info == null) {
            // Детали комнаты, которую не прочесть, — не пустой экран, а *неправильный*: пустое имя,
            // «не защищено», где переписка зашифрована. Поэтому — сбой, сказанный словами.
            Log.error(Log.Category.ROOMS, "the room would not open for its details")
            mutableState.update { it.copy(isLoadingMembers = false, failure = RoomDetailsFailure.LOAD) }
            return
        }
        adopt(info)

        infoHandle = room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                scope.launch { if (!stopped) adopt(roomInfo) else roomInfo.destroy() }
            }
        })
        localNamesJob = localNames?.let { names ->
            scope.launch {
                names.changes.collect { changed ->
                    if (changed != roomId) return@collect
                    mutableState.update { it.copy(localName = names.name(roomId), name = nameFor(it.kind, it.serverName)) }
                }
            }
        }

        // Сначала то, что есть в сторе, — экран не ждёт сервер ради уже известного; затем свежее.
        members(fresh = false)?.let(::apply)
        members(fresh = true)?.let(::apply)
        mutableState.update { it.copy(isLoadingMembers = false) }

        loadAlerts()
    }

    /** Дать чату имя на этом устройстве или вернуть серверное ([name] пустое). */
    fun renameLocally(name: String?) {
        localNames?.set(roomId, name)
    }

    /**
     * Уведомления чата: все / только упоминания / выключены. Всегда **явное** правило на сервере — у
     * чата нет четвёртого «как везде», и смена умолчания потом его не трогает.
     */
    suspend fun setAlerts(choice: RoomAlerts) {
        val previous = mutableState.value.alerts
        if (choice == previous) return
        mutableState.update { it.copy(alerts = choice) }
        try {
            withTimeLimit(LIMIT) { settings().setRoomNotificationMode(roomId, choice.mode) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "the notification rule would not stick: ${Log.describe(error)}")
            mutableState.update {
                val rolled = if (it.alerts == choice) it.copy(alerts = previous) else it
                rolled.copy(failure = RoomDetailsFailure.ALERTS)
            }
        }
    }

    /**
     * Новое имя чата. Пустое не отправляется: у чата без имени нечего показать, а стёртое поле, которое
     * просто оставили, — не решение. Новое видно сразу; отказ возвращает старое, если его не успели
     * сменить ещё раз.
     *
     * Не `suspend`: правка уходит, когда поле *оставили*, в том числе уходя с экрана, — и не должна
     * отменяться вместе с ним.
     */
    fun rename(name: String) {
        val trimmed = name.trim()
        val previous = mutableState.value.serverName
        if (trimmed.isEmpty() || trimmed == previous) return
        mutableState.update { it.copy(serverName = trimmed, name = nameFor(it.kind, trimmed)) }
        scope.launch {
            try {
                withTimeLimit(LIMIT) { room.setName(trimmed) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "rename refused: ${Log.describe(error)}")
                mutableState.update {
                    val rolled = if (it.serverName == trimmed) it.copy(serverName = previous, name = nameFor(it.kind, previous)) else it
                    rolled.copy(failure = RoomDetailsFailure.RENAME)
                }
            }
        }
    }

    /**
     * Пустое описание — *удаление*, и законное: описание, которое больше не верно, хуже, чем никакое.
     * Поэтому здесь, в отличие от [rename], пустую строку отправляют.
     */
    fun setTopic(topic: String) {
        val trimmed = topic.trim()
        val previous = mutableState.value.topic
        if (trimmed == previous.orEmpty()) return
        val attempted = trimmed.ifEmpty { null }
        mutableState.update { it.copy(topic = attempted) }
        scope.launch {
            try {
                withTimeLimit(LIMIT) { room.setTopic(trimmed) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "topic refused: ${Log.describe(error)}")
                mutableState.update {
                    val rolled = if (it.topic == attempted) it.copy(topic = previous) else it
                    rolled.copy(failure = RoomDetailsFailure.TOPIC)
                }
            }
        }
    }

    /**
     * Новая картинка чата — байты JPEG, уже обрезанные в квадрат без метаданных (`AvatarCrop`). Новый
     * адрес приносит подписка на комнату.
     */
    fun setPicture(jpeg: ByteArray) {
        mutableState.update { it.copy(isUploadingPicture = true) }
        scope.launch {
            try {
                withTimeLimit(UPLOAD_LIMIT) { room.uploadAvatar("image/jpeg", jpeg, null) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "room picture refused: ${Log.describe(error)}")
                mutableState.update { it.copy(failure = RoomDetailsFailure.PICTURE) }
            } finally {
                mutableState.update { it.copy(isUploadingPicture = false) }
            }
        }
    }

    fun removePicture() {
        val previous = mutableState.value.avatarUrl ?: return
        mutableState.update { it.copy(avatarUrl = null) }
        scope.launch {
            try {
                withTimeLimit(LIMIT) { room.removeAvatar() }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "room picture would not clear: ${Log.describe(error)}")
                mutableState.update {
                    val rolled = if (it.avatarUrl == null) it.copy(avatarUrl = previous) else it
                    rolled.copy(failure = RoomDetailsFailure.PICTURE)
                }
            }
        }
    }

    /** Картинку, которую галерея не отдала или не прочесть, — до сервера она не дошла, но сказать надо. */
    fun failPicture() = mutableState.update { it.copy(failure = RoomDetailsFailure.PICTURE) }

    fun dismissFailure() = mutableState.update { it.copy(failure = null) }

    // MARK: Кого можно трогать

    /** Может ли этот аккаунт вообще действовать над этим человеком — правила сервера, см. [RoomModeration.canManage]. */
    fun canManage(member: RoomMemberSummary): Boolean =
        RoomModeration.canManage(member.userId, member.authority, ownUserId, ownAuthority)

    /** Роли, которые можно отдать этому человеку; пусто, если комната не даёт менять роли или его не тронуть. */
    fun assignableRoles(member: RoomMemberSummary): List<RoomMemberSummary.Role> =
        if (mutableState.value.permissions.canChangeRoles && canManage(member)) {
            RoomModeration.rolesGrantableBy(ownAuthority, ::authorityOf)
        } else {
            emptyList()
        }

    /**
     * Повысить или понизить. Уровень — предложение самого SDK для роли: таблицы «роль → число» в комнате нет и быть не
     * может (Matrix называет пороги для *действий*, не для ролей; «администратор — 100» — соглашение клиентов, а не
     * факт, который комната может заявить), так что это единственный честный источник, и все другие клиенты пишут те же
     * числа. Бесконечность создателя никому не выдать — пройдёт только `Value`.
     */
    suspend fun setRole(role: RoomMemberSummary.Role, member: RoomMemberSummary) {
        if (role !in assignableRoles(member)) return
        val level = (runCatching { suggestedPowerLevelForRole(roleOf(role)) }.getOrNull() as? PowerLevel.Value)?.value
        if (level == null) {
            fail(RoomDetailsFailure.ROLE)
            return
        }
        try {
            withTimeLimit(LIMIT) { room.updatePowerLevelsForUsers(listOf(UserPowerLevelUpdate(member.userId, level))) }
            // Сразу на экране, а потом перечитано, когда комната о нём услышит — тот же довод, что у блокировки ([settle]).
            mutableState.update { state ->
                state.copy(members = state.members.map { if (it.userId == member.userId) it.copy(role = role, authority = Authority.Level(level)) else it })
            }
            settle { loaded -> loaded.joined.firstOrNull { it.userId == member.userId }?.role == role }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "role change refused: ${Log.describe(error)}")
            fail(RoomDetailsFailure.ROLE)
        }
    }

    /** Снимок комнаты с поверх положенными правилами, которых он ещё не знает; согласившиеся и просроченные — снимаются. */
    private fun withPendingRules(read: RoomRules): RoomRules {
        val now = System.currentTimeMillis()
        val levels = read.levels.toMutableMap()
        val iterator = pendingRules.entries.iterator()
        while (iterator.hasNext()) {
            val (rule, held) = iterator.next()
            val (level, since) = held
            if (levels[rule] == level || now - since > PENDING_RULE_MS) iterator.remove() else levels[rule] = level
        }
        return RoomRules(levels)
    }

    /** Люди, что ведут комнату, — администраторы и модераторы, в порядке списка. */
    val staff: List<RoomMemberSummary> get() = mutableState.value.members.filter { it.role != RoomMemberSummary.Role.MEMBER }

    /** Роли, в которые этот аккаунт может поставить правило, — ни одной, если оно ему не по плечу. */
    fun rolesFor(rule: RoomRule): List<RoomMemberSummary.Role> {
        val state = mutableState.value
        val rules = state.rules ?: return emptyList()
        if (!state.permissions.canChangeRoles) return emptyList()
        return RoomModeration.rolesForRule(rules.levels[rule] ?: 0, ownAuthority, ::authorityOf)
    }

    /** Самая низкая роль, которой правило разрешено. */
    fun roleFor(rule: RoomRule): RoomMemberSummary.Role =
        mutableState.value.rules?.role(rule, ::authorityOf) ?: RoomMemberSummary.Role.MEMBER

    /**
     * Меняет, кто может одно. Сразу на экране, и обратно, если комната откажет. Не перечитывается: кэш комнаты о
     * перемене до следующей синхронизации не знает, и перечтение вернуло бы на экран старый ответ; эхо комнаты придёт через
     * [adopt].
     */
    suspend fun setRule(rule: RoomRule, role: RoomMemberSummary.Role) {
        if (role !in rolesFor(rule)) return
        val before = mutableState.value.rules ?: return
        val level = (authorityOf(role) as? Authority.Level)?.value ?: return
        if (roleFor(rule) == role) return
        pendingRules[rule] = level to System.currentTimeMillis()
        mutableState.update { it.copy(rules = RoomRules(before.levels + (rule to level))) }
        try {
            withTimeLimit(LIMIT) { room.applyPowerLevelChanges(rule.change(level)) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "rule change refused: ${Log.describe(error)}")
            pendingRules.remove(rule)
            mutableState.update { state ->
                val rolled = if (state.rules?.levels?.get(rule) == level) state.copy(rules = before) else state
                rolled.copy(failure = RoomDetailsFailure.RULES)
            }
        }
    }

    /**
     * Входит ли любой, кто нашёл комнату, или по приглашению. У Matrix правил входа больше (стук, ограниченное
     * пространством), и приложение нарочно не предлагает ни то, ни другое: они объяснимы только словами протокола, а
     * комната с таким правилом не искажена, потому что переключатель показывается только там, где его можно *поставить*.
     */
    suspend fun setPublic(open: Boolean) {
        if (open == mutableState.value.isPublic) return
        val previous = mutableState.value.isPublic
        mutableState.update { it.copy(isPublic = open) }
        try {
            withTimeLimit(LIMIT) { room.updateJoinRules(if (open) JoinRule.Public else JoinRule.Invite) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "join rule refused: ${Log.describe(error)}")
            // Откат — «поставить, если там всё ещё то, что мы поставили»: иначе поздний отказ стирал бы более новое решение.
            mutableState.update { state ->
                val rolled = if (state.isPublic == open) state.copy(isPublic = previous) else state
                rolled.copy(failure = RoomDetailsFailure.VISIBILITY)
            }
        }
    }

    // MARK: Кто здесь

    /**
     * Позвать по Matrix ID, одного за другим; отвечает теми, кого сервер не принял. Своего `failure` не ставит: лист,
     * что просит, — поверх экрана этого стора, а предупреждение из-под листа никто не увидит. Лист говорит сам и оставляет
     * отказанных выбранными, чтобы «ещё раз» было одним нажатием.
     */
    suspend fun invite(userIds: List<String>): List<String> {
        val refused = ArrayList<String>()
        for (userId in userIds) {
            if (!RoomModeration.isMatrixId(userId)) {
                refused += userId
                continue
            }
            try {
                withTimeLimit(LIMIT) { room.inviteUserById(userId.trim()) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.error(Log.Category.ROOMS, "invitation refused: ${Log.describe(error)}")
                refused += userId
            }
        }
        return refused
    }

    suspend fun remove(member: RoomMemberSummary) {
        if (!mutableState.value.permissions.canRemove || !canManage(member)) return
        try {
            withTimeLimit(LIMIT) { room.kickUser(member.userId, null) }
            members(fresh = true)?.let(::apply)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "removal refused: ${Log.describe(error)}")
            fail(RoomDetailsFailure.REMOVE)
        }
    }

    /**
     * Блокировка — удаление, которое ещё и закрывает дверь. Отличается от [remove] и в состоянии комнаты, и в голове
     * человека, и единственная здесь без пути назад в интерфейсе, поэтому экран спрашивает до вызова.
     */
    suspend fun ban(member: RoomMemberSummary) {
        if (!mutableState.value.permissions.canBan || !canManage(member)) return
        try {
            withTimeLimit(LIMIT) { room.banUser(member.userId, null) }
            // Сразу на экране — из участников в список закрытых, — и перечитано, когда комната о нём услышит.
            mutableState.update { state ->
                state.copy(
                    members = state.members.filterNot { it.userId == member.userId },
                    banned = if (state.banned.any { it.userId == member.userId }) state.banned else state.banned + member,
                )
            }
            settle { loaded -> loaded.banned.any { it.userId == member.userId } }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "ban refused: ${Log.describe(error)}")
            fail(RoomDetailsFailure.BAN)
        }
    }

    /** Вернуть: запрет снят, и войти можно снова — не «впущен». Matrix просит того же права, что для самой блокировки. */
    suspend fun unban(member: RoomMemberSummary) {
        if (!mutableState.value.permissions.canBan) return
        val previous = mutableState.value.banned
        mutableState.update { state -> state.copy(banned = state.banned.filterNot { it.userId == member.userId }) }
        try {
            withTimeLimit(LIMIT) { room.unbanUser(member.userId, null) }
            settle { loaded -> loaded.banned.none { it.userId == member.userId } }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "unban refused: ${Log.describe(error)}")
            mutableState.update { it.copy(banned = previous) }
            fail(RoomDetailsFailure.UNBAN)
        }
    }

    /**
     * Перечитывает участников, пока комната не согласится с тем, что мы только что сделали. Сервер принимает блокировку
     * задолго до того, как копия комнаты на этом устройстве услышит о ней: она приходит синхронизацией, и до тех пор
     * `members()` отвечает из стора, где её нет, — список с заблокированным внутри, прочитанный обратно поверх экрана,
     * только что его снявшего. Поэтому читается несколько раз, ненадолго, и берётся первый ответ, что догнал. Тот, что
     * не догнал никогда, оставляет экран таким, как его положили.
     */
    private suspend fun settle(caughtUp: (Loaded) -> Boolean) {
        val mine = generation
        repeat(SETTLE_ATTEMPTS) {
            val loaded = members(fresh = true) ?: return
            if (mine != generation) return
            if (caughtUp(loaded)) {
                apply(loaded)
                return
            }
            delay(SETTLE_PAUSE)
        }
    }

    // MARK: История и адрес

    /** Кто может прочесть сказанное до прихода. */
    suspend fun setHistory(choice: HistoryAccess) {
        val state = mutableState.value
        if (!state.permissions.canChangeHistory || choice == state.history) return
        val previous = state.history
        mutableState.update { it.copy(history = choice) }
        try {
            withTimeLimit(LIMIT) { room.updateHistoryVisibility(visibility(choice)) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "history visibility refused: ${Log.describe(error)}")
            mutableState.update { if (it.history == choice) it.copy(history = previous) else it }
            fail(RoomDetailsFailure.HISTORY)
        }
    }

    /** Спрашивает сервер, есть ли комната в его каталоге. Спрашивает только экран, который это меняет. */
    suspend fun loadDirectory() {
        val visibility = runCatching { withTimeLimit(LIMIT) { room.getRoomVisibility() } }.getOrNull() ?: return
        mutableState.update { it.copy(isInDirectory = visibility is RoomVisibility.Public) }
    }

    suspend fun setInDirectory(listed: Boolean) {
        val state = mutableState.value
        if (!state.permissions.canChangeAddress || listed == state.isInDirectory) return
        val previous = state.isInDirectory
        mutableState.update { it.copy(isInDirectory = listed) }
        try {
            withTimeLimit(LIMIT) { room.updateRoomVisibility(if (listed) RoomVisibility.Public else RoomVisibility.Private) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "directory listing refused: ${Log.describe(error)}")
            mutableState.update { it.copy(isInDirectory = previous) }
            fail(RoomDetailsFailure.DIRECTORY)
        }
    }

    /** Сервер, на котором делаются адреса: то, что после двоеточия в нашем ID. */
    val addressServer: String get() = ownUserId.substringAfter(':', "")

    /**
     * Даёт комнате адрес `#имя:сервер` или снимает его ([name] пустое). Два дела на сервере, в этом порядке: адрес
     * занимается в таблице псевдонимов сервера (`publishRoomAliasInRoomDirectory` — обманчивое имя: это псевдоним, а не
     * список комнат) и только потом называется в состоянии комнаты главным. Наоборот — в комнате оказался бы адрес,
     * который ведёт в никуда или в чужую комнату. Заявка, вернувшаяся `false`, — адрес занят, и это говорится прямо.
     * Старый адрес отпускается после — но только если он был этого сервера.
     */
    suspend fun setAddress(name: String) {
        val state = mutableState.value
        if (!state.permissions.canChangeAddress) return
        val local = addressName(name)
        val alias = if (local.isEmpty()) null else "#$local:$addressServer"
        if (alias == state.canonicalAlias) return
        if (alias != null && !isRoomAliasFormatValid(alias)) {
            fail(RoomDetailsFailure.ADDRESS)
            return
        }
        val previous = state.canonicalAlias
        try {
            if (alias != null) {
                val claimed = withTimeLimit(LIMIT) { room.publishRoomAliasInRoomDirectory(alias) }
                if (!claimed) {
                    fail(RoomDetailsFailure.ADDRESS_TAKEN)
                    return
                }
            }
            withTimeLimit(LIMIT) { room.updateCanonicalAlias(alias, alternativeAliases.filter { it != alias }) }
            mutableState.update { it.copy(canonicalAlias = alias) }
            if (previous != null && previous.endsWith(":$addressServer")) {
                runCatching { withTimeLimit(LIMIT) { room.removeRoomAliasFromRoomDirectory(previous) } }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Log.error(Log.Category.ROOMS, "address refused: ${Log.describe(error)}")
            fail(RoomDetailsFailure.ADDRESS)
        }
    }

    private fun fail(failure: RoomDetailsFailure) = mutableState.update { it.copy(failure = failure) }

    /** Экран ушёл: подписки отпускаются, хендлы — нативные, GC о них не знает. */
    fun stop() {
        stopped = true
        generation += 1
        infoHandle?.cancel()
        infoHandle = null
        localNamesJob?.cancel()
        localNamesJob = null
        notifications?.close()
        notifications = null
        room.close()
    }

    private fun adopt(info: RoomInfo) {
        val server = info.serverTitle(ownUserId, strings)
        val kind = info.kind
        val levels = info.powerLevels
        val rules = levels?.let { runCatching { RoomRules(it.values()) }.getOrNull() }?.let(::withPendingRules)
        // Права приходят не всегда; без них — прежние, а не «ничего нельзя».
        val permissions = levels?.let {
            runCatching {
                RoomPermissions(
                    canRename = it.canOwnUserSendState(StateEventType.RoomName),
                    canChangeTopic = it.canOwnUserSendState(StateEventType.RoomTopic),
                    canChangePicture = it.canOwnUserSendState(StateEventType.RoomAvatar),
                    canChangeVisibility = it.canOwnUserSendState(StateEventType.RoomJoinRules),
                    canInvite = it.canOwnUserInvite(),
                    canRemove = it.canOwnUserKick(),
                    canBan = it.canOwnUserBan(),
                    canChangeRoles = it.canOwnUserSendState(StateEventType.RoomPowerLevels),
                    canChangeHistory = it.canOwnUserSendState(StateEventType.RoomHistoryVisibility),
                    canChangeAddress = it.canOwnUserSendState(StateEventType.RoomCanonicalAlias),
                )
            }.getOrNull()
        }
        mutableState.update {
            it.copy(
                isLoaded = true,
                serverName = server,
                name = nameFor(kind, server),
                topic = info.topic?.takeIf { topic -> topic.isNotBlank() },
                avatarUrl = info.pictureUrl(ownUserId),
                avatarSeed = info.avatarSeed(ownUserId),
                faces = if (kind == RoomKind.GROUP) info.faces(ownUserId) else emptyList(),
                kind = kind,
                privacy = PrivacyStatus.of(info.encryptionState),
                memberCount = info.joinedMembersCount.toInt(),
                localName = localNames?.name(roomId),
                permissions = permissions ?: it.permissions,
                rules = rules ?: it.rules,
                isPublic = info.joinRule is JoinRule.Public,
                history = historyAccess(info.historyVisibility),
                canonicalAlias = info.canonicalAlias?.takeIf { alias -> alias.isNotBlank() },
            )
        }
        alternativeAliases = info.alternativeAliases
        info.destroy()
    }

    private fun nameFor(kind: RoomKind, server: String): String =
        (if (kind == RoomKind.DIRECT) localNames?.name(roomId) else null) ?: server

    private class Loaded(val joined: List<RoomMemberSummary>, val banned: List<RoomMemberSummary>)

    private suspend fun members(fresh: Boolean): Loaded? = try {
        withTimeLimit(LIMIT) {
            val iterator = if (fresh) room.members() else room.membersNoSync()
            val all = ArrayList<RoomMember>()
            while (true) {
                val chunk = iterator.nextChunk(CHUNK.toUInt()) ?: break
                if (chunk.isEmpty()) break
                all += chunk
            }
            iterator.destroy()
            partition(all)
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Log.info(Log.Category.ROOMS, "the members did not load: ${Log.describe(error)}")
        null
    }

    /**
     * Участники, и отдельно те, кого комната закрыла, — за один проход: итератор вычерпывается раз. Сначала те, кто ведёт
     * комнату, потом остальные по имени.
     */
    private fun partition(all: List<RoomMember>): Loaded {
        val joined = ArrayList<RoomMemberSummary>()
        val banned = ArrayList<RoomMemberSummary>()
        for (member in all) {
            // Боты-служащие моста (`isServiceMember`) — не люди этого разговора.
            if (member.isServiceMember) continue
            when (member.membership) {
                is MembershipState.Join -> summary(member, invited = false)?.let(joined::add)
                is MembershipState.Invite -> summary(member, invited = true)?.let(joined::add)
                is MembershipState.Ban -> summary(member, invited = false)?.let(banned::add)
                else -> Unit
            }
        }
        return Loaded(
            joined.sortedWith(compareBy({ it.isInvited }, { it.role.ordinal }, { it.name.lowercase() })),
            banned.sortedBy { it.name.lowercase() },
        )
    }

    private fun summary(member: RoomMember, invited: Boolean): RoomMemberSummary? = RoomMemberSummary(
        userId = member.userId,
        name = member.displayName?.takeIf { it.isNotBlank() } ?: member.userId,
        avatarUrl = member.avatarUrl?.takeIf { it.isNotEmpty() },
        role = role(member.suggestedRoleForPowerLevel),
        isInvited = invited,
        authority = authority(member.powerLevel),
        isNameAmbiguous = member.isNameAmbiguous,
    )

    private fun apply(loaded: Loaded) {
        val own = loaded.joined.firstOrNull { it.userId == ownUserId }
        if (own != null) ownAuthority = own.authority
        mutableState.update { it.copy(members = loaded.joined, banned = loaded.banned) }
    }

    /** Правило этого чата, а нет своего — умолчание для такого чата (зашифрован ли, личный ли). */
    private suspend fun loadAlerts() {
        val alerts = runCatching {
            withTimeLimit(LIMIT) {
                val settings = settings()
                settings.getUserDefinedRoomNotificationMode(roomId)?.let(RoomAlerts::of)
                    ?: RoomAlerts.of(
                        settings.getDefaultRoomNotificationMode(
                            isEncrypted = mutableState.value.privacy == PrivacyStatus.PROTECTED,
                            isOneToOne = mutableState.value.isDirect,
                        ),
                    )
            }
        }.getOrNull()
        if (alerts != null && !stopped) mutableState.update { it.copy(alerts = alerts) }
    }

    /** Один `NotificationSettings` на экран: второй вызов ждёт первый, а не просит свой. */
    private suspend fun settings(): NotificationSettings = fetching.withLock {
        notifications ?: client.getNotificationSettings().also { notifications = it }
    }

    private fun authorityOf(role: RoomMemberSummary.Role): Authority =
        (runCatching { suggestedPowerLevelForRole(roleOf(role)) }.getOrNull() as? PowerLevel.Value)?.let { Authority.Level(it.value) }
            ?: Authority.Unlimited

    companion object {
        /** «@имя:сервер» и ничего другого — см. [RoomModeration.isMatrixId]. */
        fun isMatrixId(text: String): Boolean = RoomModeration.isMatrixId(text)

        /** Из того, что напечатали: правило SDK для имени адреса — строчные, дефисы вместо пробелов, ничего, на чём споткнётся URL. */
        fun addressName(typed: String): String {
            var text = typed.trim().removePrefix("#")
            text = text.substringBefore(':')
            return if (text.isEmpty()) "" else roomAliasNameFromRoomDisplayName(text)
        }

        private fun role(role: RoomMemberRole): RoomMemberSummary.Role = when (role) {
            // Создатель держит бесконечный уровень и не понижается: для читающего это тот же администратор.
            RoomMemberRole.CREATOR, RoomMemberRole.ADMINISTRATOR -> RoomMemberSummary.Role.ADMINISTRATOR
            RoomMemberRole.MODERATOR -> RoomMemberSummary.Role.MODERATOR
            else -> RoomMemberSummary.Role.MEMBER
        }

        private fun roleOf(role: RoomMemberSummary.Role): RoomMemberRole = when (role) {
            RoomMemberSummary.Role.ADMINISTRATOR -> RoomMemberRole.ADMINISTRATOR
            RoomMemberSummary.Role.MODERATOR -> RoomMemberRole.MODERATOR
            RoomMemberSummary.Role.MEMBER -> RoomMemberRole.USER
        }

        private fun authority(level: PowerLevel): Authority = when (level) {
            is PowerLevel.Infinite -> Authority.Unlimited
            is PowerLevel.Value -> Authority.Level(level.value)
        }

        private fun historyAccess(visibility: RoomHistoryVisibility): HistoryAccess = when (visibility) {
            is RoomHistoryVisibility.Invited -> HistoryAccess.INVITED
            is RoomHistoryVisibility.Joined -> HistoryAccess.JOINED
            is RoomHistoryVisibility.WorldReadable -> HistoryAccess.WORLD_READABLE
            // Shared — умолчание спецификации для комнаты, что молчит, а своё значение сервера приложению не описать;
            // показанное как умолчание, оно в худшем случае фраза о прошлом комнаты, а не оставленная открытой дверь.
            else -> HistoryAccess.SHARED
        }

        private fun visibility(access: HistoryAccess): RoomHistoryVisibility = when (access) {
            HistoryAccess.SHARED -> RoomHistoryVisibility.Shared
            HistoryAccess.INVITED -> RoomHistoryVisibility.Invited
            HistoryAccess.JOINED -> RoomHistoryVisibility.Joined
            HistoryAccess.WORLD_READABLE -> RoomHistoryVisibility.WorldReadable
        }

        private const val SETTLE_ATTEMPTS = 10
        private const val PENDING_RULE_MS = 30_000L
        private val SETTLE_PAUSE = 400.milliseconds
        private val LIMIT = 15.seconds
        /** Картинка до 1024 px — сотни килобайт; на медленной сети это не пятнадцать секунд. */
        private val UPLOAD_LIMIT = 60.seconds
        private const val CHUNK = 200
    }
}
