package wtf.iskra.core.notifications

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила уведомлений аккаунта. Своё, не из iOS: там у стора тестов нет, а правила, которые здесь
 * закреплены, — те, что описаны в `NotificationRulesStore.swift` словами: умолчание пишется в обе
 * половины, не записалось — переключатель возвращается, а возвращается он, только если его не трогали
 * после.
 */
class NotificationRulesStoreTest {
    private class FakeRules : NotificationRules {
        val defaults = mutableMapOf<Pair<Boolean, Boolean>, RoomAlerts>()
        var mention = true
        var roomMention = true
        var invite = true
        var calls = true
        var failsWrites = false
        var failsReads = false

        /** Сколько следующих записей упадёт, прежде чем они снова начнут проходить. */
        var failingWrites = 0

        /** Запись ждёт, пока тест её не отпустит, — чтобы второе нажатие успело прийти раньше. */
        var gate: CompletableDeferred<Unit>? = null

        private suspend fun write(block: () -> Unit) {
            gate?.await()
            if (failingWrites > 0) {
                failingWrites -= 1
                error("the server said no")
            }
            if (failsWrites) error("the server said no")
            block()
        }

        private fun <T> read(value: T): T = if (failsReads) error("the server is not answering") else value

        override suspend fun defaultMode(isEncrypted: Boolean, isOneToOne: Boolean) = defaults[isEncrypted to isOneToOne] ?: RoomAlerts.ALL
        override suspend fun setDefaultMode(isEncrypted: Boolean, isOneToOne: Boolean, alerts: RoomAlerts) =
            write { defaults[isEncrypted to isOneToOne] = alerts }
        override suspend fun userMention() = read(mention)
        override suspend fun setUserMention(enabled: Boolean) = write { mention = enabled }
        override suspend fun roomMention() = read(roomMention)
        override suspend fun setRoomMention(enabled: Boolean) = write { roomMention = enabled }
        override suspend fun inviteForMe() = read(invite)
        override suspend fun setInviteForMe(enabled: Boolean) = write { invite = enabled }
        override suspend fun call() = read(calls)
        override suspend fun setCall(enabled: Boolean) = write { calls = enabled }
    }

    private val rules = FakeRules()
    private val store = NotificationRulesStore { rules }

    @Test
    fun loadingReadsTheEncryptedHalf() = runTest {
        rules.defaults[true to true] = RoomAlerts.MENTIONS
        rules.defaults[false to true] = RoomAlerts.MUTED
        rules.calls = false
        store.load()
        val state = store.state.value
        assertTrue(state.isLoaded)
        assertEquals(RoomAlerts.MENTIONS, state.directChats)
        assertFalse(state.calls)
    }

    /** Сервер, который не отвечает, — экран говорит это, а не показывает умолчания поверх непрочитанного. */
    @Test
    fun nothingReadIsAFailureToLoad() = runTest {
        rules.failsReads = true
        store.load()
        assertTrue(store.state.value.didFailToLoad)
    }

    @Test
    fun aDefaultIsWrittenToBothHalves() = runTest {
        store.load()
        store.setDefault(RoomAlerts.MUTED, isOneToOne = false)
        assertEquals(RoomAlerts.MUTED, rules.defaults[true to false])
        assertEquals(RoomAlerts.MUTED, rules.defaults[false to false])
        assertEquals(RoomAlerts.MUTED, store.state.value.groupChats)
        assertNull(store.state.value.failure)
    }

    @Test
    fun aDefaultThatWouldNotStickGoesBack() = runTest {
        store.load()
        rules.failsWrites = true
        store.setDefault(RoomAlerts.MENTIONS, isOneToOne = true)
        assertEquals(RoomAlerts.ALL, store.state.value.directChats)
        assertEquals(NotificationRulesFailure.DIRECT_DEFAULT, store.state.value.failure)
        store.dismissFailure()
        assertNull(store.state.value.failure)
    }

    @Test
    fun aSwitchThatWouldNotStickGoesBack() = runTest {
        store.load()
        rules.failsWrites = true
        store.setInvitations(false)
        assertTrue(store.state.value.invitations)
        assertEquals(NotificationRulesFailure.INVITATION, store.state.value.failure)
    }

    /** Второе нажатие, пока шло первое, владеет переключателем: откат первого его не отменяет. */
    @Test
    fun aLaterFlipOwnsTheSwitch() = runTest {
        store.load()
        val gate = CompletableDeferred<Unit>()
        rules.gate = gate
        rules.failingWrites = 1
        val first = async { store.setCalls(false) }
        yield()
        // Человек передумал раньше, чем сервер ответил на первое.
        val second = async { store.setCalls(true) }
        yield()
        gate.complete(Unit)
        first.await()
        second.await()
        assertTrue(store.state.value.calls)
        // Первое всё же не записалось — и это сказано.
        assertEquals(NotificationRulesFailure.CALL, store.state.value.failure)
    }

    @Test
    fun aRollbackDoesNotUndoALaterChoice() = runTest {
        store.load()
        val gate = CompletableDeferred<Unit>()
        rules.gate = gate
        rules.failingWrites = 1
        val first = async { store.setDefault(RoomAlerts.MENTIONS, isOneToOne = false) }
        yield()
        val second = async { store.setDefault(RoomAlerts.MUTED, isOneToOne = false) }
        yield()
        gate.complete(Unit)
        first.await()
        second.await()
        // Первое не записалось, но после него выбрали «выключены» — откат к «все» это бы стёр.
        assertEquals(RoomAlerts.MUTED, store.state.value.groupChats)
    }

    @Test
    fun theSameValueWritesNothing() = runTest {
        store.load()
        rules.failsWrites = true
        store.setMentionsMe(true)
        assertNull(store.state.value.failure)
    }
}
