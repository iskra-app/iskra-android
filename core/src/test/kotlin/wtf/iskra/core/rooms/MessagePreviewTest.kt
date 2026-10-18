package wtf.iskra.core.rooms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Части строки под именем, которые не требуют натива SDK. */
class MessagePreviewTest {
    /** Стикер, пришедший обычной картинкой, — «Стикер», а не «Фото» (`StickerPicture.isSticker`). */
    @Test
    fun aStickerIsKnownByWebpOrByItsName() {
        assertTrue(MessagePreview.isSticker("image/webp", "photo.webp"))
        assertTrue(MessagePreview.isSticker("IMAGE/WEBP", null))
        assertTrue(MessagePreview.isSticker("image/png", "sticker.png"))
        assertTrue(MessagePreview.isSticker(null, "Sticker.GIF"))
        assertFalse(MessagePreview.isSticker("image/png", "sticker.jpg"))
        assertFalse(MessagePreview.isSticker("image/jpeg", "IMG_0001.jpg"))
        assertFalse(MessagePreview.isSticker(null, null))
    }

    @Test
    fun matrixTimestampsAreMilliseconds() {
        assertEquals(1_700_000_000L, MessagePreview.instant(1_700_000_000_123uL).epochSecond)
    }
}
