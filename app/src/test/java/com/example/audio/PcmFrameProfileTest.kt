package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmFrameProfileTest {
    @Test
    fun recognizesCompleteTwentyMillisecondFrames() {
        assertEquals(16000, PcmFrameProfile.inferSampleRate(640))
        assertEquals(44100, PcmFrameProfile.inferSampleRate(1764))
        assertEquals(48000, PcmFrameProfile.inferSampleRate(1920))
    }

    @Test
    fun rejectsIncompleteOrArbitraryPacketSizes() {
        assertNull(PcmFrameProfile.inferSampleRate(0))
        assertNull(PcmFrameProfile.inferSampleRate(320))
        assertNull(PcmFrameProfile.inferSampleRate(1762))
        assertNull(PcmFrameProfile.inferSampleRate(2048))
    }
}
