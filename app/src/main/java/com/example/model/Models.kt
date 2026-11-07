package com.example.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Listening(val localDeviceName: String) : ConnectionState
    data class Connecting(val deviceName: String, val deviceAddress: String) : ConnectionState
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionState
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val isPaired: Boolean = false,
    val rssi: Short? = null
)

enum class WalkieMode {
    PUSH_TO_TALK,
    OPEN_MIC
}

enum class AudioSourceMode {
    MICROPHONE,       // Voice walkie-talkie / intercom
    APP_AUDIO_RELAY   // Streams internal music, games, video, and app audio to peer
}

enum class AudioQuality(val sampleRate: Int, val label: String, val description: String) {
    HI_FI_44K(44100, "Hi-Fi 44.1 kHz (LDAC Quality)", "Studio music, video, and headset audio"),
    STANDARD_16K(16000, "Standard 16 kHz", "Low-bandwidth walkie-talkie voice")
}

enum class AudioRoute(val label: String) {
    HEADSET_OR_AUTO("Headset / Auto (3.5mm Jack)"),
    SPEAKERPHONE("Loudspeaker"),
    EARPIECE("Phone Earpiece")
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean
)
