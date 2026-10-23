package wtf.iskra.core.rooms

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.NotificationSettings
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import org.matrix.rustcomponents.sdk.RoomMember
import org.matrix.rustcomponents.sdk.StateEventType
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk.RoomMemberRole
import wtf.iskra.core.R
import wtf.iskra.core.notifications.RoomAlerts
import wtf.iskra.core.support.Log
import wtf.iskra.core.support.Strings
import wtf.iskra.core.support.withTimeLimit
import kotlin.time.Duration.Companion.seconds

/** Участник, как его показывает «О чате». */
data class RoomMemberSummary(
    val userId: String,
    val name: String,
    val avatarUrl: String?,
    val role: Role,
    /** Позван, но ещё не вошёл. */
    val isInvited: Boolean,
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
) {
    val canEditAnything: Boolean get() = canRename || canChangeTopic || canChangePicture
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
    /** Картинка уходит на сервер — лицо на экране ещё старое. */
    val isUploadingPicture: Boolean = false,
    val failure: RoomDetailsFailure? = null,
) {
    val isDirect: Boolean get() = kind == RoomKind.DIRECT
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
        members(fresh = false)?.let { list -> mutableState.update { it.copy(members = list) } }
        members(fresh = true)?.let { list -> mutableState.update { it.copy(members = list) } }
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

    /** Экран ушёл: подписки отпускаются, хендлы — нативные, GC о них не знает. */
    fun stop() {
        stopped = true
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
        // Права приходят не всегда; без них — прежние, а не «ничего нельзя».
        val permissions = levels?.let {
            runCatching {
                RoomPermissions(
                    canRename = it.canOwnUserSendState(StateEventType.RoomName),
                    canChangeTopic = it.canOwnUserSendState(StateEventType.RoomTopic),
                    canChangePicture = it.canOwnUserSendState(StateEventType.RoomAvatar),
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
            )
        }
        info.destroy()
    }

    private fun nameFor(kind: RoomKind, server: String): String =
        (if (kind == RoomKind.DIRECT) localNames?.name(roomId) else null) ?: server

    private suspend fun members(fresh: Boolean): List<RoomMemberSummary>? = try {
        withTimeLimit(LIMIT) {
            val iterator = if (fresh) room.members() else room.membersNoSync()
            val all = ArrayList<RoomMember>()
            while (true) {
                val chunk = iterator.nextChunk(CHUNK.toUInt()) ?: break
                if (chunk.isEmpty()) break
                all += chunk
            }
            iterator.destroy()
            all.mapNotNull(::summary).sortedWith(compareBy({ it.isInvited }, { it.role.ordinal }, { it.name.lowercase() }))
        }
    } catch (error: Throwable) {
        if (error is CancellationException) throw error
        Log.info(Log.Category.ROOMS, "the members did not load: ${Log.describe(error)}")
        null
    }

    private fun summary(member: RoomMember): RoomMemberSummary? {
        val invited = member.membership is MembershipState.Invite
        if (member.membership !is MembershipState.Join && !invited) return null
        // Боты-служащие моста (`isServiceMember`) — не люди этого разговора.
        if (member.isServiceMember) return null
        return RoomMemberSummary(
            userId = member.userId,
            name = member.displayName?.takeIf { it.isNotBlank() } ?: member.userId,
            avatarUrl = member.avatarUrl?.takeIf { it.isNotEmpty() },
            role = when (member.suggestedRoleForPowerLevel) {
                RoomMemberRole.CREATOR, RoomMemberRole.ADMINISTRATOR -> RoomMemberSummary.Role.ADMINISTRATOR
                RoomMemberRole.MODERATOR -> RoomMemberSummary.Role.MODERATOR
                else -> RoomMemberSummary.Role.MEMBER
            },
            isInvited = invited,
        )
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

    private companion object {
        val LIMIT = 15.seconds
        /** Картинка до 1024 px — сотни килобайт; на медленной сети это не пятнадцать секунд. */
        val UPLOAD_LIMIT = 60.seconds
        const val CHUNK = 200
    }
}
