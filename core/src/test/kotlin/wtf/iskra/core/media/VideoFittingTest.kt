package wtf.iskra.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Рамка сжатия видео: вертикальное получает столько же пикселей, меньшее не увеличивается, стороны чётные. */
class VideoFittingTest {
    @Test
    fun aLandscapeClipFitsTheLongSideOfTheBox() {
        assertEquals(1280 to 720, VideoPreparation.fitting(1920, 1080, MediaCompression.BALANCED))
    }

    @Test
    fun aPortraitClipGetsTheSameBoxTurned() {
        assertEquals(720 to 1280, VideoPreparation.fitting(1080, 1920, MediaCompression.BALANCED))
    }

    @Test
    fun aSmallClipIsNotEnlarged() {
        assertNull(VideoPreparation.fitting(640, 360, MediaCompression.BALANCED))
    }

    @Test
    fun sidesComeOutEven() {
        val (width, height) = VideoPreparation.fitting(1280, 808, MediaCompression.STRONG)!!
        assertEquals(0, width % 2)
        assertEquals(0, height % 2)
        assertEquals(854, width)
    }
}
