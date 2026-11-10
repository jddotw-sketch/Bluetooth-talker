package com.example.audio

/** BlueVoice signed mono PCM16 frames (10 ms or 20 ms). */
internal object PcmFrameProfile {
    fun inferSampleRate(pcmByteCount: Int): Int? = when (pcmByteCount) {
        320, 640 -> 16000
        1764 -> 44100
        960, 1920 -> 48000
        else -> null
    }

    fun inferFrameMs(pcmByteCount: Int): Int? = when (pcmByteCount) {
        320, 960 -> 10
        640, 1764, 1920 -> 20
        else -> null
    }
}
