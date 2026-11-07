package com.example.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioStreamManager
import com.example.bluetooth.BluetoothAudioService
import com.example.model.AudioSourceMode
import com.example.model.BluetoothDeviceInfo
import com.example.model.ChatMessage
import com.example.model.ConnectionState
import com.example.model.WalkieMode
import com.example.service.AudioRelayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WalkieTalkieUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val walkieMode: WalkieMode = WalkieMode.PUSH_TO_TALK,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.MICROPHONE,
    val isTransmitting: Boolean = false,
    val isReceivingAudio: Boolean = false,
    val isLoopbackTestActive: Boolean = false,
    val txAudioLevel: Float = 0f,
    val rxAudioLevel: Float = 0f,
    val isSpeakerphone: Boolean = true,
    val isMuted: Boolean = false,
    val micGain: Float = 1.3f,
    val rogerBeepEnabled: Boolean = true,
    val pairedDevices: List<BluetoothDeviceInfo> = emptyList(),
    val discoveredDevices: List<BluetoothDeviceInfo> = emptyList(),
    val isScanning: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val showDeviceDialog: Boolean = false,
    val showQuickMessageSheet: Boolean = false,
    val statusNotification: String? = null,
    val requiresMediaProjectionConsent: Boolean = false
)

class WalkieTalkieViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WalkieTalkieUiState())
    val uiState: StateFlow<WalkieTalkieUiState> = _uiState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rxTimeoutRunnable: Runnable? = null

    private val mediaProjectionManager = application.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager

    val audioService: BluetoothAudioService = BluetoothAudioService(application) { type, payload ->
        handleIncomingPacket(type, payload)
    }

    val audioStreamManager: AudioStreamManager = AudioStreamManager(
        context = application,
        onAudioPacketReady = { audioData ->
            if (_uiState.value.connectionState is ConnectionState.Connected) {
                audioService.sendAudio(audioData)
            }
        },
        onAmplitudeChanged = { isTx, amplitude ->
            if (isTx) {
                _uiState.update { it.copy(txAudioLevel = amplitude) }
            } else {
                _uiState.update { it.copy(rxAudioLevel = amplitude, isReceivingAudio = amplitude > 0.03f) }
                resetRxAudioTimeout()
            }
        }
    )

    init {
        checkBluetoothStatus()

        viewModelScope.launch {
            audioService.connectionState.collect { connState ->
                _uiState.update { it.copy(connectionState = connState) }
                if (connState !is ConnectionState.Connected) {
                    if (_uiState.value.isTransmitting && !_uiState.value.isLoopbackTestActive) {
                        stopTransmitting()
                    }
                    _uiState.update { it.copy(isReceivingAudio = false, rxAudioLevel = 0f) }
                }
            }
        }

        viewModelScope.launch {
            audioService.discoveredDevices.collect { devices ->
                _uiState.update { it.copy(discoveredDevices = devices) }
            }
        }

        viewModelScope.launch {
            audioService.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }

        audioStreamManager.setSpeakerphone(true)
    }

    fun checkBluetoothStatus() {
        val adapter = audioService.bluetoothAdapter
        val available = adapter != null
        val enabled = adapter?.isEnabled == true
        _uiState.update {
            it.copy(
                bluetoothAvailable = available,
                bluetoothEnabled = enabled
            )
        }
        if (enabled) {
            refreshPairedDevices()
        }
    }

    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
        if (granted) {
            checkBluetoothStatus()
            refreshPairedDevices()
        }
    }

    fun refreshPairedDevices() {
        val paired = audioService.getPairedDevices()
        _uiState.update { it.copy(pairedDevices = paired) }
    }

    fun startListening() {
        audioService.startListening()
        showStatusNotification("Listening for incoming connection...")
    }

    fun stopListening() {
        audioService.stopListening()
        showStatusNotification("Stopped listening")
    }

    fun connectToDevice(device: BluetoothDeviceInfo) {
        audioService.connectToDevice(device.address, device.name)
        _uiState.update { it.copy(showDeviceDialog = false) }
        showStatusNotification("Connecting to ${device.name}...")
    }

    fun disconnect() {
        if (_uiState.value.isTransmitting) {
            stopTransmitting()
        }
        audioService.disconnect()
        showStatusNotification("Disconnected")
    }

    fun startScan() {
        refreshPairedDevices()
        audioService.startDiscovery()
    }

    fun stopScan() {
        audioService.stopDiscovery()
    }

    fun setAudioSourceMode(mode: AudioSourceMode) {
        if (_uiState.value.audioSourceMode == mode) return

        if (_uiState.value.isTransmitting) {
            stopTransmitting()
        }

        _uiState.update { it.copy(audioSourceMode = mode) }

        if (mode == AudioSourceMode.APP_AUDIO_RELAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (audioStreamManager.mediaProjection == null) {
                    _uiState.update { it.copy(requiresMediaProjectionConsent = true) }
                } else {
                    showStatusNotification("Audio Relay active - Streaming all system & app audio")
                }
            } else {
                showStatusNotification("Audio Relay requires Android 10 or newer")
            }
        } else {
            AudioRelayService.stop(getApplication())
            showStatusNotification("Switched to Microphone Walkie-Talkie")
        }
    }

    fun getMediaProjectionIntent(): Intent? {
        return mediaProjectionManager?.createScreenCaptureIntent()
    }

    fun onMediaProjectionResult(resultCode: Int, data: Intent?) {
        _uiState.update { it.copy(requiresMediaProjectionConsent = false) }
        if (resultCode == Activity.RESULT_OK && data != null && mediaProjectionManager != null) {
            AudioRelayService.start(getApplication())
            val projection = mediaProjectionManager.getMediaProjection(resultCode, data)
            audioStreamManager.mediaProjection = projection
            showStatusNotification("Audio Relay ready - Streams YouTube, music & game audio")
        } else {
            _uiState.update { it.copy(audioSourceMode = AudioSourceMode.MICROPHONE) }
            showStatusNotification("Audio capture permission denied")
        }
    }

    fun onPttPressed() {
        if (_uiState.value.isTransmitting) return
        startTransmitting()
    }

    fun onPttReleased() {
        if (_uiState.value.walkieMode == WalkieMode.PUSH_TO_TALK && _uiState.value.audioSourceMode != AudioSourceMode.APP_AUDIO_RELAY) {
            stopTransmitting()
        }
    }

    fun toggleWalkieMode() {
        val newMode = if (_uiState.value.walkieMode == WalkieMode.PUSH_TO_TALK) {
            WalkieMode.OPEN_MIC
        } else {
            WalkieMode.PUSH_TO_TALK
        }

        if (_uiState.value.isTransmitting) {
            stopTransmitting()
        }

        _uiState.update { it.copy(walkieMode = newMode) }
        showStatusNotification(
            if (newMode == WalkieMode.OPEN_MIC) "Hands-Free Mic mode active" else "Push-To-Talk mode active"
        )
    }

    fun toggleTransmission() {
        if (_uiState.value.isTransmitting) {
            stopTransmitting()
        } else {
            startTransmitting()
        }
    }

    private fun startTransmitting() {
        val isConnected = _uiState.value.connectionState is ConnectionState.Connected
        val isLoopback = _uiState.value.isLoopbackTestActive

        if (!isConnected && !isLoopback) {
            showStatusNotification("Connect to another phone or turn on Mic Test to speak")
            return
        }

        _uiState.update { it.copy(isTransmitting = true) }

        if (isConnected && !isLoopback) {
            audioService.sendPttStart()
        }

        if (_uiState.value.audioSourceMode == AudioSourceMode.MICROPHONE) {
            audioStreamManager.playPttStartChirp()
        }

        audioStreamManager.startRecording(
            sourceMode = _uiState.value.audioSourceMode,
            loopbackMode = isLoopback
        )
    }

    private fun stopTransmitting() {
        _uiState.update { it.copy(isTransmitting = false, txAudioLevel = 0f) }
        audioStreamManager.stopRecording()

        val isConnected = _uiState.value.connectionState is ConnectionState.Connected
        val isLoopback = _uiState.value.isLoopbackTestActive

        if (isConnected && !isLoopback) {
            audioService.sendPttEnd()
        }

        if (_uiState.value.audioSourceMode == AudioSourceMode.MICROPHONE) {
            audioStreamManager.playRogerBeep()
        }
    }

    fun toggleLoopbackTest() {
        val current = _uiState.value.isLoopbackTestActive
        if (current) {
            if (_uiState.value.isTransmitting) {
                stopTransmitting()
            }
            _uiState.update { it.copy(isLoopbackTestActive = false) }
            showStatusNotification("Mic Test disabled")
        } else {
            _uiState.update { it.copy(isLoopbackTestActive = true) }
            showStatusNotification("Mic Test active - Speak to hear audio playback")
        }
    }

    fun toggleSpeakerphone() {
        val newSpeaker = !_uiState.value.isSpeakerphone
        _uiState.update { it.copy(isSpeakerphone = newSpeaker) }
        audioStreamManager.setSpeakerphone(newSpeaker)
        showStatusNotification(if (newSpeaker) "Speakerphone ON" else "Earpiece ON")
    }

    fun toggleMute() {
        val newMuted = !_uiState.value.isMuted
        _uiState.update { it.copy(isMuted = newMuted) }
        audioStreamManager.isMuted = newMuted
        showStatusNotification(if (newMuted) "Microphone muted" else "Microphone unmuted")
    }

    fun setMicGain(gain: Float) {
        val clamped = gain.coerceIn(0.8f, 3.0f)
        _uiState.update { it.copy(micGain = clamped) }
        audioStreamManager.micGain = clamped
    }

    fun toggleRogerBeep() {
        val newBeep = !_uiState.value.rogerBeepEnabled
        _uiState.update { it.copy(rogerBeepEnabled = newBeep) }
        audioStreamManager.rogerBeepEnabled = newBeep
        showStatusNotification(if (newBeep) "Roger Beep enabled" else "Roger Beep disabled")
    }

    fun sendQuickMessage(text: String) {
        if (text.isBlank()) return
        val isConnected = _uiState.value.connectionState is ConnectionState.Connected
        if (!isConnected) {
            showStatusNotification("Connect to a device to send messages")
            return
        }

        audioService.sendTextMessage(text)
        val msg = ChatMessage(
            senderName = "You",
            message = text,
            isFromMe = true
        )
        _uiState.update { it.copy(chatMessages = it.chatMessages + msg) }
    }

    fun setDeviceDialogVisible(show: Boolean) {
        _uiState.update { it.copy(showDeviceDialog = show) }
        if (show) {
            refreshPairedDevices()
        }
    }

    fun setQuickMessageSheetVisible(show: Boolean) {
        _uiState.update { it.copy(showQuickMessageSheet = show) }
    }

    fun clearStatusNotification() {
        _uiState.update { it.copy(statusNotification = null) }
    }

    private fun showStatusNotification(message: String) {
        _uiState.update { it.copy(statusNotification = message) }
    }

    private fun handleIncomingPacket(type: Byte, payload: ByteArray) {
        when (type) {
            BluetoothAudioService.TYPE_AUDIO -> {
                audioStreamManager.playIncomingAudio(payload)
                _uiState.update { it.copy(isReceivingAudio = true) }
                resetRxAudioTimeout()
            }
            BluetoothAudioService.TYPE_PTT_START -> {
                audioStreamManager.playPttStartChirp()
                _uiState.update { it.copy(isReceivingAudio = true) }
            }
            BluetoothAudioService.TYPE_PTT_END -> {
                audioStreamManager.playRogerBeep()
                _uiState.update { it.copy(isReceivingAudio = false, rxAudioLevel = 0f) }
            }
            BluetoothAudioService.TYPE_TEXT -> {
                val text = payload.toString(Charsets.UTF_8)
                val sender = when (val conn = _uiState.value.connectionState) {
                    is ConnectionState.Connected -> conn.deviceName
                    else -> "Remote Device"
                }
                val msg = ChatMessage(
                    senderName = sender,
                    message = text,
                    isFromMe = false
                )
                _uiState.update { it.copy(chatMessages = it.chatMessages + msg) }
                showStatusNotification("[$sender]: $text")
            }
        }
    }

    private fun resetRxAudioTimeout() {
        rxTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        rxTimeoutRunnable = Runnable {
            _uiState.update { it.copy(isReceivingAudio = false, rxAudioLevel = 0f) }
        }
        mainHandler.postDelayed(rxTimeoutRunnable!!, 400)
    }

    override fun onCleared() {
        super.onCleared()
        rxTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        AudioRelayService.stop(getApplication())
        audioStreamManager.release()
        audioService.cleanup()
    }
}
