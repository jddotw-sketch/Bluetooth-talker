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

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderName: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isFromMe: Boolean
)
