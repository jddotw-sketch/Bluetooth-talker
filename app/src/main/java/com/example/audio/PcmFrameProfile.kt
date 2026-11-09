package com.example.audio

/**
 * BlueVoice transports exactly 20 ms of signed PCM16 mono per audio packet.
 * The decoded packet size can recover the capture rate when a Bluetooth
 * configuration frame is delayed or lost.
 */
internal object PcmFrameProfile {
    fun inferSampleRate(pcmByteCount: Int): Int? = when (pcmByteCount) {
        640 -> 16000  // 320 samples at 16 kHz
        1764 -> 44100 // 882 samples at 44.1 kHz
        1920 -> 48000 // 960 samples at 48 kHz
        else -> null
    }
}
