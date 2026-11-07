package com.example.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioQuality
import com.example.model.AudioRoute
import com.example.model.AudioSourceMode
import com.example.model.BluetoothDeviceInfo
import com.example.model.ConnectionState
import com.example.model.WalkieMode
import com.example.ui.components.AudioSettingsSheet
import com.example.ui.components.DeviceSelectionSheet
import com.example.ui.components.PttButton
import com.example.ui.components.QuickMessageSheet
import com.example.ui.components.VisualizerCanvas
import com.example.ui.theme.AmberTransmitting
import com.example.ui.theme.BlueAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.GreenConnected
import com.example.ui.theme.RedMuted
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateCardBorder
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateLight
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.WalkieTalkieViewModel
import kotlinx.coroutines.delay

@Composable
fun WalkieTalkieScreen(
    viewModel: WalkieTalkieViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Permission launcher for standard Bluetooth/Audio
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        viewModel.setPermissionsGranted(allGranted)
    }

    // MediaProjection launcher for Audio Relay (system/app audio capture)
    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionResult(result.resultCode, result.data)
    }

    // Trigger MediaProjection consent when Audio Relay is selected
    LaunchedEffect(uiState.requiresMediaProjectionConsent) {
        if (uiState.requiresMediaProjectionConsent) {
            val intent = viewModel.getMediaProjectionIntent()
            if (intent != null) {
                try {
                    mediaProjectionLauncher.launch(intent)
                } catch (e: Exception) {
                    viewModel.onMediaProjectionResult(android.app.Activity.RESULT_CANCELED, null)
                }
            } else {
                viewModel.onMediaProjectionResult(android.app.Activity.RESULT_CANCELED, null)
            }
        }
    }

    // Discoverable intent launcher
    val discoverableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.startListening()
    }

    LaunchedEffect(Unit) {
        val requiredPerms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            perms.toTypedArray()
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        permissionsLauncher.launch(requiredPerms)
    }

    // Auto-clear notification toast after 3.5 seconds
    LaunchedEffect(uiState.statusNotification) {
        if (uiState.statusNotification != null) {
            delay(3500)
            viewModel.clearStatusNotification()
        }
    }

    val isConnected = uiState.connectionState is ConnectionState.Connected

    Scaffold(
        containerColor = SlateDark,
        contentWindowInsets = WindowInsets.statusBars,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Header Bar
                TopBarSection(
                    connectionState = uiState.connectionState,
                    chatCount = uiState.chatMessages.size,
                    onOpenDevices = { viewModel.setDeviceDialogVisible(true) },
                    onOpenChat = { viewModel.setQuickMessageSheetVisible(true) },
                    onOpenSettings = { viewModel.setAudioSettingsDialogVisible(true) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Audio Source Mode Switcher: Microphone Walkie-Talkie vs App Audio Relay
                AudioSourceSelectorBar(
                    currentSource = uiState.audioSourceMode,
                    onSelectSource = { viewModel.setAudioSourceMode(it) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Status Notification Banner
                AnimatedVisibility(
                    visible = uiState.statusNotification != null,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    uiState.statusNotification?.let { msg ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SlateSurface),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanNeon.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = CyanNeon,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = msg,
                                    color = TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Tactical Radio Display HUD (With Hi-Fi badge, Audio Jack Headset state & Auto-connect tag)
                RadioHudCard(
                    connectionState = uiState.connectionState,
                    walkieMode = uiState.walkieMode,
                    audioSourceMode = uiState.audioSourceMode,
                    audioQuality = uiState.audioQuality,
                    audioRoute = uiState.audioRoute,
                    isHeadsetPlugged = uiState.isHeadsetPlugged,
                    autoConnectEnabled = uiState.autoConnectEnabled,
                    isTransmitting = uiState.isTransmitting,
                    isReceiving = uiState.isReceivingAudio,
                    isLoopbackActive = uiState.isLoopbackTestActive,
                    txLevel = uiState.txAudioLevel,
                    rxLevel = uiState.rxAudioLevel
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Animated Waveform Visualizer
                VisualizerCanvas(
                    isTransmitting = uiState.isTransmitting,
                    isReceiving = uiState.isReceivingAudio,
                    txLevel = uiState.txAudioLevel,
                    rxLevel = uiState.rxAudioLevel
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Walkie-Talkie Core PTT Button
                PttButton(
                    walkieMode = uiState.walkieMode,
                    audioSourceMode = uiState.audioSourceMode,
                    isTransmitting = uiState.isTransmitting,
                    isReceiving = uiState.isReceivingAudio,
                    isMuted = uiState.isMuted,
                    isConnected = isConnected,
                    isLoopbackActive = uiState.isLoopbackTestActive,
                    txLevel = uiState.txAudioLevel,
                    onPttPress = { viewModel.onPttPressed() },
                    onPttRelease = { viewModel.onPttReleased() },
                    onToggleTransmission = { viewModel.toggleTransmission() }
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Mode Selector Segmented Chips (Only applicable in Microphone mode)
                if (uiState.audioSourceMode == AudioSourceMode.MICROPHONE) {
                    ModeSelectorBar(
                        currentMode = uiState.walkieMode,
                        onToggleMode = { viewModel.toggleWalkieMode() }
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Tactical Control Toolbar (Headset/Speakerphone, Mute, Roger, Mic Test, Gain)
                ControlsToolbar(
                    currentRoute = uiState.audioRoute,
                    isHeadsetPlugged = uiState.isHeadsetPlugged,
                    isMuted = uiState.isMuted,
                    isRogerBeep = uiState.rogerBeepEnabled,
                    isLoopback = uiState.isLoopbackTestActive,
                    micGain = uiState.micGain,
                    onCycleRoute = {
                        val nextRoute = when (uiState.audioRoute) {
                            AudioRoute.HEADSET_OR_AUTO -> AudioRoute.SPEAKERPHONE
                            AudioRoute.SPEAKERPHONE -> AudioRoute.EARPIECE
                            AudioRoute.EARPIECE -> AudioRoute.HEADSET_OR_AUTO
                        }
                        viewModel.setAudioRoute(nextRoute)
                    },
                    onToggleMute = { viewModel.toggleMute() },
                    onToggleRoger = { viewModel.toggleRogerBeep() },
                    onToggleLoopback = { viewModel.toggleLoopbackTest() },
                    onGainChanged = { viewModel.setMicGain(it) }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Peer Connection Quick Action Footer
                ConnectionFooterCard(
                    connectionState = uiState.connectionState,
                    autoConnectEnabled = uiState.autoConnectEnabled,
                    lastTargetName = uiState.lastConnectedDeviceName,
                    onOpenDevices = { viewModel.setDeviceDialogVisible(true) },
                    onStartListening = { viewModel.startListening() },
                    onDisconnect = { viewModel.disconnect() }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Bottom Sheets
            if (uiState.showDeviceDialog) {
                DeviceSelectionSheet(
                    connectionState = uiState.connectionState,
                    pairedDevices = uiState.pairedDevices,
                    discoveredDevices = uiState.discoveredDevices,
                    isScanning = uiState.isScanning,
                    onDismiss = { viewModel.setDeviceDialogVisible(false) },
                    onStartScan = { viewModel.startScan() },
                    onStopScan = { viewModel.stopScan() },
                    onStartListening = { viewModel.startListening() },
                    onStopListening = { viewModel.stopListening() },
                    onRequestDiscoverable = {
                        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        }
                        try {
                            discoverableLauncher.launch(discoverableIntent)
                        } catch (e: Exception) {
                            viewModel.startListening()
                        }
                    },
                    onConnectDevice = { device -> viewModel.connectToDevice(device) },
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            if (uiState.showQuickMessageSheet) {
                QuickMessageSheet(
                    messages = uiState.chatMessages,
                    isConnected = isConnected,
                    onSendMessage = { viewModel.sendQuickMessage(it) },
                    onDismiss = { viewModel.setQuickMessageSheetVisible(false) }
                )
            }

            if (uiState.showAudioSettingsDialog) {
                AudioSettingsSheet(
                    audioQuality = uiState.audioQuality,
                    audioRoute = uiState.audioRoute,
                    isHeadsetPlugged = uiState.isHeadsetPlugged,
                    autoConnectEnabled = uiState.autoConnectEnabled,
                    lastConnectedName = uiState.lastConnectedDeviceName,
                    lastConnectedAddr = uiState.lastConnectedDeviceAddress,
                    onSetQuality = { viewModel.setAudioQuality(it) },
                    onSetRoute = { viewModel.setAudioRoute(it) },
                    onToggleAutoConnect = { viewModel.toggleAutoConnect() },
                    onConnectLastDevice = {
                        val addr = uiState.lastConnectedDeviceAddress
                        val name = uiState.lastConnectedDeviceName
                        if (addr != null) {
                            viewModel.connectToDevice(BluetoothDeviceInfo(name ?: addr, addr))
                        }
                    },
                    onDismiss = { viewModel.setAudioSettingsDialogVisible(false) }
                )
            }
        }
    }
}

@Composable
fun AudioSourceSelectorBar(
    currentSource: AudioSourceMode,
    onSelectSource: (AudioSourceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilledTonalButton(
                onClick = { onSelectSource(AudioSourceMode.APP_AUDIO_RELAY) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (currentSource == AudioSourceMode.APP_AUDIO_RELAY) Color(0xFF651FFF) else Color.Transparent
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("source_relay_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Headphones,
                    contentDescription = null,
                    tint = if (currentSource == AudioSourceMode.APP_AUDIO_RELAY) Color.White else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Audio Relay (Music & Apps)",
                    color = if (currentSource == AudioSourceMode.APP_AUDIO_RELAY) Color.White else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = { onSelectSource(AudioSourceMode.MICROPHONE) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (currentSource == AudioSourceMode.MICROPHONE) BlueAccent else Color.Transparent
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .testTag("source_mic_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (currentSource == AudioSourceMode.MICROPHONE) Color.White else TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Walkie-Talkie",
                    color = if (currentSource == AudioSourceMode.MICROPHONE) Color.White else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun TopBarSection(
    connectionState: ConnectionState,
    chatCount: Int,
    onOpenDevices: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(BlueAccent, CyanNeon))),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Radio,
                    contentDescription = "BlueVoice Logo",
                    tint = Color.Black,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "BlueVoice",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Hi-Fi Relay & Walkie-Talkie",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Audio Settings button
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SlateCard)
                    .testTag("open_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Audio & Connection Settings",
                    tint = CyanNeon,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Chat button
            IconButton(
                onClick = onOpenChat,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(SlateCard)
                    .testTag("open_chat_button")
            ) {
                Box(contentAlignment = Alignment.TopEnd) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "Radio Messages",
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                    if (chatCount > 0) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(AmberTransmitting)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Devices button
            Button(
                onClick = onOpenDevices,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (connectionState) {
                        is ConnectionState.Connected -> GreenConnected
                        is ConnectionState.Listening -> AmberTransmitting
                        else -> SlateCard
                    }
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .height(38.dp)
                    .testTag("open_devices_button")
            ) {
                Icon(
                    imageVector = if (connectionState is ConnectionState.Connected) {
                        Icons.Default.BluetoothConnected
                    } else {
                        Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    tint = if (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Listening) {
                        Color.Black
                    } else {
                        CyanNeon
                    },
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connected -> "Connected"
                        is ConnectionState.Listening -> "Waiting..."
                        is ConnectionState.Connecting -> "Connecting"
                        ConnectionState.Disconnected -> "Connect"
                    },
                    color = if (connectionState is ConnectionState.Connected || connectionState is ConnectionState.Listening) {
                        Color.Black
                    } else {
                        TextPrimary
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun RadioHudCard(
    connectionState: ConnectionState,
    walkieMode: WalkieMode,
    audioSourceMode: AudioSourceMode,
    audioQuality: AudioQuality,
    audioRoute: AudioRoute,
    isHeadsetPlugged: Boolean,
    autoConnectEnabled: Boolean,
    isTransmitting: Boolean,
    isReceiving: Boolean,
    isLoopbackActive: Boolean,
    txLevel: Float,
    rxLevel: Float,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            when {
                isTransmitting && audioSourceMode == AudioSourceMode.APP_AUDIO_RELAY -> CyanNeon
                isTransmitting -> AmberTransmitting
                isReceiving -> GreenConnected
                isLoopbackActive -> CyanNeon
                else -> SlateCardBorder
            }
        ),
        modifier = modifier
            .fillMaxWidth()
            .testTag("radio_hud_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Channel / Status Badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isTransmitting && audioSourceMode == AudioSourceMode.APP_AUDIO_RELAY -> CyanNeon
                                    isTransmitting -> AmberTransmitting
                                    isReceiving -> GreenConnected
                                    connectionState is ConnectionState.Connected -> GreenConnected
                                    isLoopbackActive -> CyanNeon
                                    else -> SlateLight
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (audioQuality == AudioQuality.HI_FI_44K) "HI-FI 44.1 kHz • ZERO-LAG SYNC" else "16 kHz VOICE • ZERO-LAG",
                        color = if (audioQuality == AudioQuality.HI_FI_44K) CyanNeon else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }

                // Output Route or Headset state tag
                Text(
                    text = if (isHeadsetPlugged) "🎧 3.5mm HEADSET" else audioRoute.label.uppercase(),
                    color = if (isHeadsetPlugged) GreenConnected else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Display
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> connectionState.deviceName
                            is ConnectionState.Listening -> "Waiting for peer..."
                            is ConnectionState.Connecting -> "Connecting..."
                            ConnectionState.Disconnected -> if (isLoopbackActive) "Local Loopback" else "No Peer Connected"
                        },
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "Peer: ${connectionState.deviceAddress} • ${if (autoConnectEnabled) "Auto-Reconnecting" else "Manual"}"
                            is ConnectionState.Listening -> "Listening on RFCOMM (SPP)"
                            else -> if (isLoopbackActive) "Testing microphone & speaker locally" else "Tap 'Connect' or use Auto-Connect"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }

                // Audio VU Level Meter
                Column(horizontalAlignment = Alignment.End) {
                    val displayLevel = if (isTransmitting) txLevel else rxLevel
                    val percentage = (displayLevel * 100).toInt().coerceIn(0, 100)
                    Text(
                        text = "$percentage%",
                        color = if (displayLevel > 0.05f) CyanNeon else TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "VU LEVEL",
                        color = TextSecondary,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ModeSelectorBar(
    currentMode: WalkieMode,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { if (currentMode != WalkieMode.PUSH_TO_TALK) onToggleMode() },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (currentMode == WalkieMode.PUSH_TO_TALK) BlueAccent else Color.Transparent
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "Push-To-Talk",
                    color = if (currentMode == WalkieMode.PUSH_TO_TALK) Color.White else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = { if (currentMode != WalkieMode.OPEN_MIC) onToggleMode() },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (currentMode == WalkieMode.OPEN_MIC) CyanNeon else Color.Transparent
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "Hands-Free (Open Mic)",
                    color = if (currentMode == WalkieMode.OPEN_MIC) Color.Black else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ControlsToolbar(
    currentRoute: AudioRoute,
    isHeadsetPlugged: Boolean,
    isMuted: Boolean,
    isRogerBeep: Boolean,
    isLoopback: Boolean,
    micGain: Float,
    onCycleRoute: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleRoger: () -> Unit,
    onToggleLoopback: () -> Unit,
    onGainChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var showGainSlider by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Audio Jack Headset / Speakerphone cycle button
            TacticalButton(
                icon = when (currentRoute) {
                    AudioRoute.HEADSET_OR_AUTO -> Icons.Default.Headphones
                    AudioRoute.SPEAKERPHONE -> Icons.Default.VolumeUp
                    AudioRoute.EARPIECE -> Icons.Default.VolumeDown
                },
                label = when (currentRoute) {
                    AudioRoute.HEADSET_OR_AUTO -> if (isHeadsetPlugged) "3.5mm Jack" else "Headset/Auto"
                    AudioRoute.SPEAKERPHONE -> "Speaker"
                    AudioRoute.EARPIECE -> "Earpiece"
                },
                isActive = isHeadsetPlugged || currentRoute == AudioRoute.HEADSET_OR_AUTO,
                activeColor = if (isHeadsetPlugged) GreenConnected else CyanNeon,
                onClick = onCycleRoute,
                testTag = "cycle_route_button"
            )

            // Mic Mute toggle
            TacticalButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                label = if (isMuted) "Muted" else "Mic On",
                isActive = isMuted,
                activeColor = RedMuted,
                onClick = onToggleMute,
                testTag = "toggle_mute_button"
            )

            // Roger Beep toggle
            TacticalButton(
                icon = Icons.Default.Notifications,
                label = "Roger Beep",
                isActive = isRogerBeep,
                activeColor = AmberTransmitting,
                onClick = onToggleRoger,
                testTag = "toggle_roger_button"
            )

            // Loopback / Mic Test
            TacticalButton(
                icon = Icons.Default.SettingsVoice,
                label = "Mic Test",
                isActive = isLoopback,
                activeColor = GreenConnected,
                onClick = onToggleLoopback,
                testTag = "toggle_mic_test_button"
            )

            // Mic Gain
            TacticalButton(
                icon = Icons.Default.GraphicEq,
                label = "Gain ${String.format("%.1fx", micGain)}",
                isActive = showGainSlider,
                activeColor = BlueAccent,
                onClick = { showGainSlider = !showGainSlider },
                testTag = "toggle_gain_slider_button"
            )
        }

        // Expanded Gain Slider Card
        AnimatedVisibility(visible = showGainSlider) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Microphone Software Gain",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = String.format("%.1fx boost", micGain),
                            color = CyanNeon,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = micGain,
                        onValueChange = onGainChanged,
                        valueRange = 0.8f..3.0f,
                        steps = 10,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanNeon,
                            activeTrackColor = CyanNeon,
                            inactiveTrackColor = SlateLight
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun TacticalButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isActive) activeColor.copy(alpha = 0.2f) else SlateCard)
                .border(1.dp, if (isActive) activeColor else SlateCardBorder, CircleShape)
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) activeColor else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isActive) activeColor else TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ConnectionFooterCard(
    connectionState: ConnectionState,
    autoConnectEnabled: Boolean,
    lastTargetName: String?,
    onOpenDevices: () -> Unit,
    onStartListening: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = if (connectionState is ConnectionState.Connected) GreenConnected else CyanNeon,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "Direct Link Active"
                            is ConnectionState.Listening -> "Listening for Peer"
                            is ConnectionState.Connecting -> "Establishing Link"
                            ConnectionState.Disconnected -> if (autoConnectEnabled && lastTargetName != null) "Auto-Connect Ready" else "P2P Bluetooth Link"
                        },
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (connectionState) {
                            is ConnectionState.Connected -> "Hi-Fi audio streaming with zero cutouts"
                            is ConnectionState.Listening -> "Ready for incoming connection"
                            else -> if (autoConnectEnabled && lastTargetName != null) "Will reconnect to $lastTargetName" else "Tap 'Pair / Scan' or Settings"
                        },
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            when (connectionState) {
                is ConnectionState.Connected -> {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = RedMuted),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Disconnect", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                is ConnectionState.Listening -> {
                    Button(
                        onClick = onOpenDevices,
                        colors = ButtonDefaults.buttonColors(containerColor = AmberTransmitting),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Waiting...", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Button(
                        onClick = onOpenDevices,
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Pair / Scan", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
