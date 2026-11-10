package com.example.audio

/**
 * Low latency is opt-in to preserve the known-good, jitter-tolerant audio path.
 * Windows SPP senders use the existing 20 ms / stable mode.
 */
internal object AudioLatencyProfile {
    fun capturePacketMs(sampleRate: Int, lowLatency: Boolean): Int =
        // 44.1 kHz / 10 ms is 441 samples; IMA ADPCM encodes 2 samples/byte,
        // so a 10 ms packet would acquire a phantom sample on decode.
        if (lowLatency && sampleRate != 44100) 10 else 20

    fun maxOutboundAudioPackets(lowLatency: Boolean): Int = if (lowLatency) 6 else 15

    fun startupAdditionalPackets(lowLatency: Boolean): Int = if (lowLatency) 1 else 3

    fun startupDeadlineMs(lowLatency: Boolean, packetMs: Int): Long =
        if (lowLatency) maxOf(15, packetMs + 8).toLong() else 85L

    fun maxReceiveQueuePackets(lowLatency: Boolean, packetMs: Int): Int =
        if (lowLatency) maxOf(4, 90 / packetMs) else 30

    fun initialTrackBufferMs(lowLatency: Boolean): Int = if (lowLatency) 60 else 200
}
