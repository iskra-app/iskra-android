package wtf.iskra.features.support

import kotlin.test.Test
import kotlin.test.assertEquals

/** Арифметика за `AvatarCropper` — тесты `AvatarCropTests.swift`. Вырезка (Bitmap) — на устройстве. */
class AvatarCropTest {
    private val width = 4000f
    private val height = 3000f

    @Test
    fun restingCropIsTheMiddleEdgeToEdge() {
        val crop = AvatarCrop(width, height, side = 300f)
        assertEquals(CropSquare(500f, 0f, 3000f), crop.cropSquare)
    }

    @Test
    fun offsetIsHeldIn() {
        val far = AvatarCrop(width, height, side = 300f, offsetX = 10_000f, offsetY = 10_000f).cropSquare
        assertEquals(0f, far.left)
        assertEquals(0f, far.top)
        val left = AvatarCrop(width, height, side = 300f, offsetX = -10_000f).cropSquare
        assertEquals(width, left.right)
    }

    @Test
    fun zoomTakesLessAndStaysInRange() {
        assertEquals(1500f, AvatarCrop(width, height, side = 300f, zoom = 2f).cropSquare.side)
        assertEquals(1f, AvatarCrop.clampedZoom(0.2f))
        assertEquals(AvatarCrop.MAX_ZOOM, AvatarCrop.clampedZoom(99f))
    }

    @Test
    fun outputIsNoLargerThanTheMost() {
        assertEquals(AvatarCrop.MAX_SIDE, AvatarCrop(width, height, side = 300f).outputSide)
        assertEquals(500, AvatarCrop(500f, 800f, side = 300f).outputSide)
    }
}
