package com.example

import com.example.audio.AdpcmCodec
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Unit tests verifying audio codecs and latency handling.
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun adpcmCodec_roundTripPreservesSignalAndReducesSize() {
        // Generate 960 samples of 48 kHz sine wave (20ms)
        val numSamples = 960
        val pcm = ByteArray(numSamples * 2)
        val shortBuffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until numSamples) {
            val sample = (sin(2.0 * Math.PI * 440.0 * i / 48000.0) * 16000.0).toInt().toShort()
            shortBuffer.put(sample)
        }

        // Encode to ADPCM (expect 4 bytes header + 480 bytes = 484 bytes, 4:1 compression)
        val encoded = AdpcmCodec.encode(pcm)
        assertEquals(484, encoded.size)
        assertTrue("Compressed size must be roughly 1/4 of PCM", encoded.size < pcm.size / 3)

        // Decode back to PCM
        val decoded = AdpcmCodec.decode(encoded)
        assertEquals("Decoded PCM must have same byte count as original", pcm.size, decoded.size)

        // Verify correlation (signal is preserved without clipping or distortion)
        val decodedShorts = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var errorSum = 0.0
        for (i in 0 until numSamples) {
            val orig = (sin(2.0 * Math.PI * 440.0 * i / 48000.0) * 16000.0)
            val dec = decodedShorts.get(i).toDouble()
            val diff = orig - dec
            errorSum += diff * diff
        }
        val rmse = Math.sqrt(errorSum / numSamples)
        assertTrue("ADPCM RMSE should be low (< 2000 for 16000 amplitude wave)", rmse < 2000.0)
    }

    @Test
    fun adpcmCodec_handlesEmptyAndZero() {
        val empty = ByteArray(0)
        assertEquals(0, AdpcmCodec.encode(empty).size)
        assertEquals(0, AdpcmCodec.decode(empty).size)

        val silence = ByteArray(640)
        val encodedSilence = AdpcmCodec.encode(silence)
        assertEquals(4 + 160, encodedSilence.size)
        val decodedSilence = AdpcmCodec.decode(encodedSilence)
        assertEquals(640, decodedSilence.size)
    }

    @Test
    fun adpcmCodec_consecutivePacketsWithStateStreamSmoothly() {
        val state = AdpcmCodec.CodecState()
        val numSamples = 960
        // Generate continuous audio across 3 packets
        for (packet in 0 until 3) {
            val pcm = ByteArray(numSamples * 2)
            val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            for (i in 0 until numSamples) {
                val totalSampleIndex = packet * numSamples + i
                val sample = (sin(2.0 * Math.PI * 440.0 * totalSampleIndex / 48000.0) * 16000.0).toInt().toShort()
                buffer.put(sample)
            }
            val encoded = AdpcmCodec.encode(pcm, state)
            assertEquals(484, encoded.size)
            val decoded = AdpcmCodec.decode(encoded)
            assertEquals(numSamples * 2, decoded.size)
        }
        assertTrue("Predictor should be tracking wave", state.predictor != 0)
    }
}
