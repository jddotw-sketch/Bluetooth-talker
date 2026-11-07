package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BluetoothDeviceInfo
import com.example.model.ConnectionState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionSheet(
    connectionState: ConnectionState,
    pairedDevices: List<BluetoothDeviceInfo>,
    discoveredDevices: List<BluetoothDeviceInfo>,
    isScanning: Boolean,
    onDismiss: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onRequestDiscoverable: () -> Unit,
    onConnectDevice: (BluetoothDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SlateDark,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SlateLight)
            )
        },
        modifier = modifier.testTag("device_selection_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bluetooth,
                        contentDescription = "Bluetooth",
                        tint = CyanNeon,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Bluetooth Devices",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (connectionState) {
                                is ConnectionState.Connected -> "Connected to ${connectionState.deviceName}"
                                is ConnectionState.Listening -> "Waiting for peer to connect..."
                                is ConnectionState.Connecting -> "Connecting to ${connectionState.deviceName}..."
                                ConnectionState.Disconnected -> "Connect to another phone"
                            },
                            color = when (connectionState) {
                                is ConnectionState.Connected -> GreenConnected
                                is ConnectionState.Listening -> AmberTransmitting
                                is ConnectionState.Connecting -> CyanNeon
                                ConnectionState.Disconnected -> TextSecondary
                            },
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_sheet_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Quick Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when (connectionState) {
                    is ConnectionState.Connected -> {
                        Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(containerColor = RedMuted),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("disconnect_button")
                        ) {
                            Text("Disconnect", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    is ConnectionState.Listening -> {
                        Button(
                            onClick = onStopListening,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberTransmitting),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("stop_listening_button")
                        ) {
                            Text("Stop Waiting", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        Button(
                            onClick = onStartListening,
                            colors = ButtonDefaults.buttonColors(containerColor = SlateSurface),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_listening_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = null,
                                tint = CyanNeon,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Wait for Peer", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }

                OutlinedButton(
                    onClick = onRequestDiscoverable,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("make_discoverable_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = null,
                        tint = AmberTransmitting,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Be Discoverable", fontSize = 12.sp)
                }

                Button(
                    onClick = if (isScanning) onStopScan else onStartScan,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) RedMuted else BlueAccent
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("scan_button")
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Stop", color = Color.White, fontSize = 12.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Scan", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick Setup Guide
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "How to connect two phones:",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "1. Open BlueVoice on both phones.\n2. On Phone A: tap 'Wait for Peer' or 'Be Discoverable'.\n3. On Phone B: tap 'Scan', pick Phone A, and tap Connect.\n4. Hold the round PTT button to stream live audio!",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // List of Devices
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                // Paired Devices
                item {
                    Text(
                        text = "PAIRED PHONES & DEVICES (${pairedDevices.size})",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }

                if (pairedDevices.isEmpty()) {
                    item {
                        Text(
                            text = "No paired devices found. Tap 'Scan' or pair via Android Bluetooth Settings.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(pairedDevices) { device ->
                        DeviceItemRow(
                            device = device,
                            isConnected = (connectionState as? ConnectionState.Connected)?.deviceAddress == device.address,
                            onConnect = { onConnectDevice(device) }
                        )
                    }
                }

                // Discovered Devices
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "NEARBY SCANNED DEVICES (${discoveredDevices.size})",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                        if (isScanning) {
                            Text(
                                text = "Searching...",
                                color = CyanNeon,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                if (discoveredDevices.isEmpty()) {
                    item {
                        Text(
                            text = if (isScanning) "Searching for nearby Bluetooth devices..." else "Tap 'Scan' above to find nearby phones.",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                } else {
                    items(discoveredDevices) { device ->
                        DeviceItemRow(
                            device = device,
                            isConnected = (connectionState as? ConnectionState.Connected)?.deviceAddress == device.address,
                            onConnect = { onConnectDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItemRow(
    device: BluetoothDeviceInfo,
    isConnected: Boolean,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) SlateSurface else SlateCard
        ),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isConnected) GreenConnected else SlateCardBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onConnect)
            .testTag("device_item_${device.address}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) GreenConnected.copy(alpha = 0.2f) else SlateSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Bluetooth,
                        contentDescription = null,
                        tint = if (isConnected) GreenConnected else CyanNeon,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = device.name,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${device.address} ${if (device.isPaired) "• Paired" else ""}",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (isConnected) {
                Text(
                    text = "CONNECTED",
                    color = GreenConnected,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Connect", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
