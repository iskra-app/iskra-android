package wtf.iskra.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import wtf.iskra.core.media.MediaSendJobs.Item
import wtf.iskra.core.media.MediaSendJobs.Phase
import wtf.iskra.core.media.MediaSendJobs.QueueEvent
import wtf.iskra.core.media.MediaSendJobs.What
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Видео или альбом, за которым следят дальше экрана. Очередь разыгрывается руками: проверяется учёт. Порт `MediaSendJobsTests`. */
class MediaSendJobsTest {
    private fun jobs() = MediaSendJobs(
        roomFor = { null },
        look = { MediaSendJobs.RoomLook("Кухня", "!kitchen") },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private fun MediaSendJobs.first() = jobs.value.first()

    @Test
    fun onePhotoIsNotFollowedButAVideoThreePhotosOrABigFileAre() {
        assertTrue(!MediaSendJobs.isWorthFollowing(listOf(Item())))
        assertTrue(!MediaSendJobs.isWorthFollowing(listOf(Item(), Item())))
        assertTrue(MediaSendJobs.isWorthFollowing(listOf(Item(), Item(), Item())))
        assertTrue(MediaSendJobs.isWorthFollowing(listOf(Item(isVideo = true))))
        assertTrue(MediaSendJobs.isWorthFollowing(listOf(Item(isFile = true, bytes = 20L * 1024 * 1024))))
        assertTrue(!MediaSendJobs.isWorthFollowing(listOf(Item(isFile = true, bytes = 1024))))
        assertNull(jobs().begin("!r", listOf(Item())))
    }

    @Test
    fun anAlbumGoesPreparingUploadingSentAndTheBarOnlyMovesForward() {
        val jobs = jobs()
        val id = assertNotNull(jobs.begin("!r", listOf(Item(), Item(), Item())))
        assertEquals(What.Photos(3), jobs.first().what)
        assertEquals("Кухня", jobs.first().roomName)
        assertEquals(Phase.PREPARING, jobs.first().phase)
        assertEquals(0.0, jobs.first().progress)

        var last = 0.0
        fun moved() {
            val now = jobs.first().progress
            assertTrue(now >= last, "полоса пошла назад: $last → $now")
            last = now
        }

        for (transaction in listOf("t1", "t2", "t3")) {
            jobs.handed(id, accepted = true)
            jobs.receive(QueueEvent.Created(transaction), "!r")
            moved()
        }
        jobs.doneHanding(id)
        assertEquals(Phase.UPLOADING, jobs.first().phase)
        // Подготовка кончена: её доля полосы полна, доля загрузки пуста.
        assertEquals(MediaSendJobs.PREPARATION_SHARE, jobs.first().progress, 0.0001)

        jobs.receive(QueueEvent.Progress("t1", 0.5), "!r"); moved()
        jobs.receive(QueueEvent.Sent("t1"), "!r"); moved()
        jobs.receive(QueueEvent.Sent("t2"), "!r"); moved()
        assertEquals(Phase.UPLOADING, jobs.first().phase)
        jobs.receive(QueueEvent.Sent("t3"), "!r")
        assertEquals(Phase.SENT, jobs.first().phase)
        assertEquals(1.0, jobs.first().progress)
    }

    @Test
    fun cancelIsOfferedOnlyOnceTheQueueHoldsEverything() {
        val jobs = jobs()
        val id = assertNotNull(jobs.begin("!r", listOf(Item(isVideo = true), Item(isVideo = true))))
        jobs.handed(id, accepted = true)
        jobs.receive(QueueEvent.Created("t1"), "!r")
        assertTrue(!MediaSendJobs.canCancel(jobs.first()), "вторую вещь ещё готовят")
        jobs.handed(id, accepted = true)
        jobs.receive(QueueEvent.Created("t2"), "!r")
        jobs.doneHanding(id)
        assertTrue(MediaSendJobs.canCancel(jobs.first()))
        jobs.receive(QueueEvent.Sent("t1"), "!r")
        assertTrue(MediaSendJobs.canCancel(jobs.first()), "вторая ещё в пути")
        jobs.receive(QueueEvent.Sent("t2"), "!r")
        assertTrue(!MediaSendJobs.canCancel(jobs.first()), "всё ушло")
    }

    @Test
    fun anotherRoomsOrALaterMessageIsNotTheJobs() {
        val jobs = jobs()
        val id = assertNotNull(jobs.begin("!r", listOf(Item(isVideo = true))))
        jobs.receive(QueueEvent.Created("elsewhere"), "!other")
        jobs.handed(id, accepted = true)
        jobs.receive(QueueEvent.Created("mine"), "!r")
        jobs.doneHanding(id)
        // Текст, набранный после видео, — тоже не его.
        jobs.receive(QueueEvent.Created("words"), "!r")
        jobs.receive(QueueEvent.Sent("mine"), "!r")
        assertEquals(Phase.SENT, jobs.first().phase)
    }

    @Test
    fun aFinalRefusalFailsTheJobAndAPauseForTheNetworkDoesNot() {
        val jobs = jobs()
        val id = assertNotNull(jobs.begin("!r", listOf(Item(isVideo = true))))
        jobs.handed(id, accepted = true)
        jobs.receive(QueueEvent.Created("t"), "!r")
        jobs.doneHanding(id)
        jobs.receive(QueueEvent.Failed("t", recoverable = true), "!r")
        assertEquals(Phase.UPLOADING, jobs.first().phase)
        jobs.receive(QueueEvent.Failed("t", recoverable = false), "!r")
        assertEquals(Phase.FAILED, jobs.first().phase)
    }

    @Test
    fun aVideoTheSdkRefusedBeforeTheQueueIsAFailedJob() {
        val jobs = jobs()
        val id = assertNotNull(jobs.begin("!r", listOf(Item(isVideo = true))))
        jobs.handed(id, accepted = false)
        jobs.doneHanding(id)
        assertEquals(Phase.FAILED, jobs.first().phase)
    }

    @Test
    fun aCancelledItemStopsBeingWaitedFor() {
        val jobs = jobs()
        val id = assertNotNull(jobs.begin("!r", listOf(Item(isVideo = true), Item())))
        jobs.handed(id, accepted = true)
        jobs.receive(QueueEvent.Created("video"), "!r")
        jobs.handed(id, accepted = true)
        jobs.receive(QueueEvent.Created("photo"), "!r")
        jobs.doneHanding(id)
        jobs.receive(QueueEvent.Sent("video"), "!r")
        jobs.receive(QueueEvent.Cancelled("photo"), "!r")
        assertEquals(Phase.SENT, jobs.first().phase)
        assertEquals(What.Media(2), jobs.first().what)
    }

    @Test
    fun theJobsFlowMovesAsAJobMoves() {
        val jobs = jobs()
        val seen = mutableListOf<Double>()
        val id = jobs.begin("!r", listOf(Item(isVideo = true)))
        seen += jobs.first().progress
        jobs.handed(id, accepted = true)
        seen += jobs.first().progress
        assertTrue(seen[1] > seen[0])
    }
}
