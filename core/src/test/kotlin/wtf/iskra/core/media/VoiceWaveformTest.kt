package wtf.iskra.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWaveformTest {
    @Test
    fun silenceAndTheFloorAreZeroAndFullScaleIsOne() {
        assertEquals(0f, VoiceWaveform.level(0), 0f)
        // −50 дБ от полной шкалы — тихая речь: ровно пол.
        assertEquals(0f, VoiceWaveform.level(103), 0.01f)
        assertEquals(1f, VoiceWaveform.level(32767), 0.001f)
        assertTrue(VoiceWaveform.level(3276) in 0.55f..0.65f)
    }

    @Test
    fun aShortRecordingIsPaddedNotStretched() {
        val out = VoiceWaveform.resampled(listOf(0.5f, 1f))
        assertEquals(VoiceWaveform.SAMPLE_COUNT, out.size)
        assertEquals(listOf(0.5f, 1f, 0f), out.take(3))
    }

    @Test
    fun aLongRecordingIsAveragedPerBucket() {
        val samples = List(VoiceWaveform.SAMPLE_COUNT * 2) { if (it % 2 == 0) 1f else 0f }
        val out = VoiceWaveform.resampled(samples)
        assertEquals(VoiceWaveform.SAMPLE_COUNT, out.size)
        assertTrue(out.all { it == 0.5f })
    }

    @Test
    fun theWireIsZeroTo1024() {
        assertEquals(listOf(0f, 512f, 1024f), VoiceWaveform.wire(listOf(0f, 0.5f, 2f)))
    }
}
