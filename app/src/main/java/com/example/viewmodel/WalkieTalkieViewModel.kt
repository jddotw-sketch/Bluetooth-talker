package com.example.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioStreamManager
import com.example.bluetooth.BluetoothAudioService
import com.example.model.AudioQuality
import com.example.model.AudioRoute
import com.example.model.AudioSourceMode
import com.example.model.BluetoothDeviceInfo
import com.example.model.ChatMessage
import com.example.model.ConnectionState
import com.example.model.WalkieMode
import com.example.service.AudioRelayService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

data class WalkieTalkieUiState(
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val walkieMode: WalkieMode = WalkieMode.PUSH_TO_TALK,
    val audioSourceMode: AudioSourceMode = AudioSourceMode.APP_AUDIO_RELAY, // Default to Audio Relay as requested
    val audioQuality: AudioQuality = AudioQuality.HI_FI_44K,               // Default to Hi-Fi LDAC-style 44.1kHz
    val audioRoute: AudioRoute = AudioRoute.HEADSET_OR_AUTO,              // Default to 3.5mm Headset / Auto
    val isHeadsetPlugged: Boolean = false,
    val autoConnectEnabled: Boolean = true,
    val lastConnectedDeviceName: String? = null,
    val lastConnectedDeviceAddress: String? = null,
    val isTransmitting: Boolean = false,
    val isReceivingAudio: Boolean = false,
    val isLoopbackTestActive: Boolean = false,
    val txAudioLevel: Float = 0f,
    val rxAudioLevel: Float = 0f,
    val isMuted: Boolean = false,
    val micGain: Float = 1.2f,
    val rogerBeepEnabled: Boolean = true,
    val pairedDevices: List<BluetoothDeviceInfo> = emptyList(),
    val discoveredDevices: List<BluetoothDeviceInfo> = emptyList(),
    val isScanning: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val showDeviceDialog: Boolean = false,
    val showQuickMessageSheet: Boolean = false,
    val showAudioSettingsDialog: Boolean = false,
    val statusNotification: String? = null,
    val requiresMediaProjectionConsent: Boolean = false
)

class WalkieTalkieViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "bluevoice_prefs"
        private const val KEY_AUTO_CONNECT = "auto_connect_enabled"
        private const val KEY_LAST_ADDR = "last_connected_addr"
        private const val KEY_LAST_NAME = "last_connected_name"
        private const val KEY_QUALITY = "audio_quality"
        private const val KEY_ROUTE = "audio_route"
    }

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        WalkieTalkieUiState(
            autoConnectEnabled = prefs.getBoolean(KEY_AUTO_CONNECT, true),
            lastConnectedDeviceAddress = prefs.getString(KEY_LAST_ADDR, null),
            lastConnectedDeviceName = prefs.getString(KEY_LAST_NAME, null),
            audioQuality = try {
                AudioQuality.valueOf(prefs.getString(KEY_QUALITY, AudioQuality.HI_FI_44K.name)!!)
            } catch (e: Exception) {
                AudioQuality.HI_FI_44K
            },
            audioRoute = try {
                AudioRoute.valueOf(prefs.getString(KEY_ROUTE, AudioRoute.HEADSET_OR_AUTO.name)!!)
            } catch (e: Exception) {
                AudioRoute.HEADSET_OR_AUTO
            }
        )
    )
    val uiState: StateFlow<WalkieTalkieUiState> = _uiState.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rxTimeoutRunnable: Runnable? = null
    private var autoReconnectJob: Job? = null

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
        },
        onHeadsetStateChanged = { isPlugged ->
            _uiState.update { it.copy(isHeadsetPlugged = isPlugged) }
            showStatusNotification(
                if (isPlugged) "Headset plugged in 🎧 - Audio routed to 3.5mm jack" else "Headset unplugged"
            )
        }
    )

    init {
        audioStreamManager.setAudioQuality(_uiState.value.audioQuality)
        audioStreamManager.setAudioOutputRoute(_uiState.value.audioRoute)

        checkBluetoothStatus()

        viewModelScope.launch {
            audioService.connectionState.collect { connState ->
                _uiState.update { it.copy(connectionState = connState) }
                when (connState) {
                    is ConnectionState.Connected -> {
                        autoReconnectJob?.cancel()
                        // Save last connected device for auto-connect
                        prefs.edit()
                            .putString(KEY_LAST_ADDR, connState.deviceAddress)
                            .putString(KEY_LAST_NAME, connState.deviceName)
                            .apply()
                        _uiState.update {
                            it.copy(
                                lastConnectedDeviceAddress = connState.deviceAddress,
                                lastConnectedDeviceName = connState.deviceName
                            )
                        }
                        // Send current audio quality config to peer
                        audioService.sendConfig(_uiState.value.audioQuality.sampleRate)
                        showStatusNotification("Connected to ${connState.deviceName} (Hi-Fi Link Ready)")
                    }
                    is ConnectionState.Disconnected -> {
                        if (_uiState.value.isTransmitting && !_uiState.value.isLoopbackTestActive) {
                            stopTransmitting()
                        }
                        _uiState.update { it.copy(isReceivingAudio = false, rxAudioLevel = 0f) }
                        // Trigger auto-reconnect if enabled
                        scheduleAutoReconnect()
                    }
                    else -> {}
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
    }

    private fun scheduleAutoReconnect() {
        if (!_uiState.value.autoConnectEnabled) return
        val targetAddr = _uiState.value.lastConnectedDeviceAddress ?: return
        val targetName = _uiState.value.lastConnectedDeviceName

        autoReconnectJob?.cancel()
        autoReconnectJob = viewModelScope.launch {
            delay(3500)
            if (_uiState.value.connectionState is ConnectionState.Disconnected && _uiState.value.autoConnectEnabled) {
                showStatusNotification("Auto-connecting to $targetName...")
                audioService.connectToDevice(targetAddr, targetName)
            }
        }
    }

    fun toggleAutoConnect() {
        val newSetting = !_uiState.value.autoConnectEnabled
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, newSetting).apply()
        _uiState.update { it.copy(autoConnectEnabled = newSetting) }
        showStatusNotification(if (newSetting) "Auto-Connect Enabled" else "Auto-Connect Disabled")
        if (newSetting && _uiState.value.connectionState is ConnectionState.Disconnected) {
            scheduleAutoReconnect()
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        prefs.edit().putString(KEY_QUALITY, quality.name).apply()
        _uiState.update { it.copy(audioQuality = quality) }
        audioStreamManager.setAudioQuality(quality)
        if (_uiState.value.connectionState is ConnectionState.Connected) {
            audioService.sendConfig(quality.sampleRate)
        }
        showStatusNotification("Quality: ${quality.label}")
    }

    fun setAudioRoute(route: AudioRoute) {
        prefs.edit().putString(KEY_ROUTE, route.name).apply()
        _uiState.update { it.copy(audioRoute = route) }
        audioStreamManager.setAudioOutputRoute(route)
        showStatusNotification("Output: ${route.label}")
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
            // Auto connect on launch if address saved
            if (_uiState.value.autoConnectEnabled && _uiState.value.lastConnectedDeviceAddress != null && _uiState.value.connectionState is ConnectionState.Disconnected) {
                scheduleAutoReconnect()
            }
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
        autoReconnectJob?.cancel()
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
            showStatusNotification("Connect to another phone or turn on Mic Test to stream")
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

    fun setAudioSettingsDialogVisible(show: Boolean) {
        _uiState.update { it.copy(showAudioSettingsDialog = show) }
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
            BluetoothAudioService.TYPE_CONFIG -> {
                if (payload.size >= 4) {
                    val sampleRate = ByteBuffer.wrap(payload).int
                    audioStreamManager.setRemoteSampleRate(sampleRate)
                    val quality = if (sampleRate >= 32000) AudioQuality.HI_FI_44K else AudioQuality.STANDARD_16K
                    _uiState.update { it.copy(audioQuality = quality) }
                    showStatusNotification("Remote configured: ${quality.label}")
                }
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
        autoReconnectJob?.cancel()
        AudioRelayService.stop(getApplication())
        audioStreamManager.release()
        audioService.cleanup()
    }
}
