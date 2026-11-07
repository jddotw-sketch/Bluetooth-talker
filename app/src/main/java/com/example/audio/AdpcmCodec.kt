package com.example.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Standard IMA-ADPCM encoder and decoder.
 * Compresses 16-bit linear PCM audio to 4-bit ADPCM (4:1 compression ratio).
 *
 * Reduces Bluetooth bandwidth by 75%:
 * - 48.0 kHz: 96 KB/s -> 24 KB/s
 * - 44.1 kHz: 88 KB/s -> 22 KB/s
 * - 16.0 kHz: 32 KB/s -> 8 KB/s
 *
 * Each encoded packet contains a 4-byte header:
 * - 2 bytes: predictor (initial 16-bit PCM sample)
 * - 1 byte: step index (0..88)
 * - 1 byte: reserved (0x00)
 * Followed by packed nibbles.
 *
 * Packets are self-contained so that any dropped or skipped packet over Bluetooth
 * immediately resets the decoder without audio drift or glitches.
 */
object AdpcmCodec {

    private val INDEX_TABLE = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )

    private val STEP_SIZE_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
        19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
        50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
        130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
        337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
        876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
        2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
        5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    fun encode(pcmData: ByteArray): ByteArray {
        val numSamples = pcmData.size / 2
        if (numSamples == 0) return ByteArray(0)

        val shortBuffer = ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        val samples = ShortArray(numSamples)
        shortBuffer.get(samples)

        // 4 bytes header: 2 bytes initial sample, 1 byte index, 1 byte reserved
        val encodedLength = 4 + (numSamples + 1) / 2
        val out = ByteArray(encodedLength)

        var predictedSample = samples[0].toInt()
        var index = 0

        // Header
        out[0] = (predictedSample and 0xFF).toByte()
        out[1] = ((predictedSample shr 8) and 0xFF).toByte()
        out[2] = index.toByte()
        out[3] = 0.toByte()

        var outPos = 4
        var bufferStep = 0
        var tempByte = 0

        for (i in 0 until numSamples) {
            val sample = samples[i].toInt()
            var step = STEP_SIZE_TABLE[index]
            var diff = sample - predictedSample
            var sign = 0

            if (diff < 0) {
                sign = 8
                diff = -diff
            }

            var delta = 0
            var vpdiff = step shr 3

            if (diff >= step) {
                delta = delta or 4
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 2
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 1
                vpdiff += step
            }

            delta = delta or sign

            if (sign != 0) {
                predictedSample -= vpdiff
            } else {
                predictedSample += vpdiff
            }
            predictedSample = predictedSample.coerceIn(-32768, 32767)

            index += INDEX_TABLE[delta]
            index = index.coerceIn(0, 88)

            if (bufferStep == 0) {
                tempByte = delta and 0x0F
                bufferStep = 1
            } else {
                out[outPos++] = ((delta shl 4) or tempByte).toByte()
                bufferStep = 0
            }
        }

        if (bufferStep == 1 && outPos < out.size) {
            out[outPos] = tempByte.toByte()
        }

        return out
    }

    fun decode(adpcmData: ByteArray): ByteArray {
        if (adpcmData.size <= 4) return ByteArray(0)

        // Read header
        val rawSample = (adpcmData[0].toInt() and 0xFF) or ((adpcmData[1].toInt() and 0xFF) shl 8)
        var predictedSample = rawSample.toShort().toInt()
        var index = (adpcmData[2].toInt() and 0xFF).coerceIn(0, 88)

        val numNibbles = (adpcmData.size - 4) * 2
        val samples = ShortArray(numNibbles)
        var samplePos = 0

        for (i in 4 until adpcmData.size) {
            val b = adpcmData[i].toInt() and 0xFF
            val nibble1 = b and 0x0F
            val nibble2 = (b shr 4) and 0x0F

            // Decode nibble 1
            var step = STEP_SIZE_TABLE[index]
            var vpdiff = step shr 3
            if ((nibble1 and 4) != 0) vpdiff += step
            if ((nibble1 and 2) != 0) vpdiff += (step shr 1)
            if ((nibble1 and 1) != 0) vpdiff += (step shr 2)
            if ((nibble1 and 8) != 0) {
                predictedSample -= vpdiff
            } else {
                predictedSample += vpdiff
            }
            predictedSample = predictedSample.coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[nibble1]).coerceIn(0, 88)
            samples[samplePos++] = predictedSample.toShort()

            if (samplePos < numNibbles) {
                // Decode nibble 2
                step = STEP_SIZE_TABLE[index]
                vpdiff = step shr 3
                if ((nibble2 and 4) != 0) vpdiff += step
                if ((nibble2 and 2) != 0) vpdiff += (step shr 1)
                if ((nibble2 and 1) != 0) vpdiff += (step shr 2)
                if ((nibble2 and 8) != 0) {
                    predictedSample -= vpdiff
                } else {
                    predictedSample += vpdiff
                }
                predictedSample = predictedSample.coerceIn(-32768, 32767)
                index = (index + INDEX_TABLE[nibble2]).coerceIn(0, 88)
                samples[samplePos++] = predictedSample.toShort()
            }
        }

        val out = ByteArray(samplePos * 2)
        ByteBuffer.wrap(out)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples, 0, samplePos)
        return out
    }
}
