package wtf.iskra.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Меры кружка: от них зависит, узнает ли получатель его кругом, и сколько он весит. */
class VideoNoteTest {
    @Test
    fun `битрейт — десятая бита на пиксель в секунду`() {
        assertEquals(691_200, VideoNote.bitRate(480, 480))
        assertEquals(VideoNote.bitRate(960, 960), VideoNote.bitRate(480, 480) * 4)
    }

    @Test
    fun `минута кружка — около пяти мегабайт, а не тридцати`() {
        val bytes = (VideoNote.bitRate(VideoNote.SIDE, VideoNote.SIDE) + VideoNote.AUDIO_BIT_RATE) / 8L * 60
        assertTrue("весит $bytes", bytes in 4_000_000L..7_000_000L)
    }

    @Test
    fun `запись останавливается раньше потолка, чтобы контейнер не вышел за минуту`() {
        assertTrue(VideoNote.RECORDING_LIMIT_MS < VideoNote.MAXIMUM_MS)
    }
}
