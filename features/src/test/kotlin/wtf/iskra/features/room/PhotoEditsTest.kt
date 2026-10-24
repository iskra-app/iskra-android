package wtf.iskra.features.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhotoEditsTest {
    private val point = PhotoEdits.Point(10f, 20f)

    @Test
    fun aQuarterTurnSwapsTheSidesAndCarriesAStroke() {
        val edits = PhotoEdits(width = 100, height = 50, strokes = listOf(PhotoEdits.Stroke(0, 4f, listOf(point))))
        val turned = edits.turned()
        assertEquals(50, turned.width)
        assertEquals(100, turned.height)
        // (x, y) на картинке шириной 100 → (y, 100 − x)
        assertEquals(PhotoEdits.Point(20f, 90f), turned.strokes.single().points.single())
    }

    @Test
    fun fourTurnsComeBackToTheStart() {
        val edits = PhotoEdits(width = 100, height = 50, labels = listOf(PhotoEdits.Label(1, "a", 0, point, 12f)), crop = PhotoEdits.Frame(5f, 6f, 70f, 40f))
        assertEquals(edits, edits.turned().turned().turned().turned())
    }

    @Test
    fun theFrameStaysInsideTheImageAndNeverCollapses() {
        val bounds = PhotoEdits.Frame(0f, 0f, 100f, 100f)
        val frame = PhotoEdits.Frame(10f, 10f, 90f, 90f)
        val body = CropHandle.BODY.move(frame, 50f, 50f, bounds)
        assertEquals(PhotoEdits.Frame(20f, 20f, 100f, 100f), body)
        val squeezed = CropHandle.BOTTOM_RIGHT.move(frame, -500f, -500f, bounds)
        assertEquals(frame.left + PhotoEdits.MIN_SIDE, squeezed.right)
        assertEquals(frame.top + PhotoEdits.MIN_SIDE, squeezed.bottom)
    }

    @Test
    fun aCornerIsPickedBeforeTheBody() {
        val frame = PhotoEdits.Frame(10f, 10f, 90f, 90f)
        assertEquals(CropHandle.TOP_LEFT, CropHandle.at(12f, 12f, frame, reach = 8f))
        assertEquals(CropHandle.BODY, CropHandle.at(50f, 50f, frame, reach = 8f))
        assertNull(CropHandle.at(200f, 200f, frame, reach = 8f))
    }
}
