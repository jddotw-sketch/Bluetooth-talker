package com.example.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmFrameProfileTest {
    @Test
    fun recognizesCompleteTenAndTwentyMillisecondFrames() {
        assertEquals(16000, PcmFrameProfile.inferSampleRate(320))
        assertEquals(16000, PcmFrameProfile.inferSampleRate(640))
        assertEquals(44100, PcmFrameProfile.inferSampleRate(1764))
        assertEquals(48000, PcmFrameProfile.inferSampleRate(960))
        assertEquals(48000, PcmFrameProfile.inferSampleRate(1920))
        assertEquals(10, PcmFrameProfile.inferFrameMs(320))
        assertEquals(20, PcmFrameProfile.inferFrameMs(640))
        assertEquals(20, PcmFrameProfile.inferFrameMs(1764))
        assertEquals(10, PcmFrameProfile.inferFrameMs(960))
        assertEquals(20, PcmFrameProfile.inferFrameMs(1920))
    }

    @Test
    fun rejectsIncompleteOrArbitraryPacketSizes() {
        assertNull(PcmFrameProfile.inferSampleRate(0))
        assertNull(PcmFrameProfile.inferSampleRate(318))
        assertNull(PcmFrameProfile.inferSampleRate(1762))
        assertNull(PcmFrameProfile.inferSampleRate(2048))
        assertNull(PcmFrameProfile.inferFrameMs(0))
    }

    @Test
    fun latencyProfileRetainsSmoothModeAndHandlesOddSampleRates() {
        assertEquals(10, AudioLatencyProfile.capturePacketMs(16000, true))
        assertEquals(10, AudioLatencyProfile.capturePacketMs(48000, true))
        assertEquals(20, AudioLatencyProfile.capturePacketMs(44100, true))
        assertEquals(20, AudioLatencyProfile.capturePacketMs(16000, false))
        assertEquals(6, AudioLatencyProfile.maxOutboundAudioPackets(true))
        assertEquals(15, AudioLatencyProfile.maxOutboundAudioPackets(false))
        assertEquals(1, AudioLatencyProfile.startupAdditionalPackets(true))
        assertEquals(3, AudioLatencyProfile.startupAdditionalPackets(false))
    }
}
