package wtf.iskra.design

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Порт `AlbumMosaicTests.swift`: куда встают картинки альбома. */
class AlbumMosaicTest {
    private val width = 280f
    private val gap = 2f

    private fun frames(ratios: List<Float>, maxHeight: Float = 10_000f) = AlbumMosaic.frames(ratios, width, gap, maxHeight)

    private fun AlbumMosaic.Frame.intersects(other: AlbumMosaic.Frame, inset: Float = 0.5f): Boolean =
        x + inset < other.right - inset && other.x + inset < right - inset && y + inset < other.bottom - inset && other.y + inset < bottom - inset

    @Test
    fun everyPictureGetsAPlaceAndNoTwoOverlap() {
        for (count in 2..10) {
            val ratios = (0 until count).map { listOf(1.5f, 0.75f, 1f, 1.33f, 0.6f)[it % 5] }
            val placed = frames(ratios)
            assertEquals(count, placed.size)
            for (frame in placed) {
                assertTrue(frame.x >= -0.5f && frame.right <= width + 0.5f, "$count: $frame")
                assertTrue(frame.width > 20 && frame.height > 20, "$count: $frame")
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) {
                assertTrue(!placed[i].intersects(placed[j]), "$count: ${placed[i]} и ${placed[j]}")
            }
        }
    }

    @Test
    fun twoSquarePhotosSideBySideKeepTheirShape() {
        val placed = frames(listOf(1f, 1f))
        assertEquals(placed[0].y, placed[1].y)
        assertTrue(abs(placed[0].width - placed[0].height) < 1)
    }

    @Test
    fun aTallFirstPhotoGetsTheLeftColumn() {
        val placed = frames(listOf(0.6f, 1.5f, 1.5f))
        assertTrue(placed[0].x == 0f && placed[0].y == 0f)
        assertTrue(placed[1].x > placed[0].right && placed[2].x > placed[0].right)
        assertTrue(placed[2].y > placed[1].bottom)
    }

    @Test
    fun aWideFirstPhotoGoesOnTop() {
        val placed = frames(listOf(2f, 1f, 1f))
        assertEquals(width, placed[0].width)
        assertTrue(placed[1].y > placed[0].bottom && placed[1].y == placed[2].y)
    }

    @Test
    fun anAlbumTallerThanAllowedIsSqueezed() {
        val placed = frames(listOf(0.5f, 0.5f, 0.5f, 0.5f), maxHeight = 200f)
        assertTrue((placed.maxOfOrNull { it.bottom } ?: 0f) <= 200.5f)
    }

    @Test
    fun splitsCoverEveryWayOfCuttingIntoRowsOfUpToFour() {
        assertEquals(4, AlbumMosaic.splits(3).size)
        assertTrue(AlbumMosaic.splits(5).all { it.sum() == 5 && it.all { n -> n <= 4 } })
    }
}
