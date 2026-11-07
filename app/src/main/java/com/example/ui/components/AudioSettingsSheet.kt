package com.example.ui.components

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.example.model.AudioQuality
import com.example.model.AudioRoute
import com.example.ui.theme.AmberTransmitting
import com.example.ui.theme.BlueAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.GreenConnected
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateCardBorder
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateLight
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(
    audioQuality: AudioQuality,
    audioRoute: AudioRoute,
    isHeadsetPlugged: Boolean,
    autoConnectEnabled: Boolean,
    lastConnectedName: String?,
    lastConnectedAddr: String?,
    onSetQuality: (AudioQuality) -> Unit,
    onSetRoute: (AudioRoute) -> Unit,
    onToggleAutoConnect: () -> Unit,
    onConnectLastDevice: () -> Unit,
    onDismiss: () -> Unit,
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
        modifier = modifier.testTag("audio_settings_sheet")
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
                        imageVector = Icons.Default.HighQuality,
                        contentDescription = "Audio Settings",
                        tint = CyanNeon,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Audio & Connection Settings",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hi-Fi LDAC quality, audio routing & auto-connect",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Auto-Connect Mode
            Text(
                text = "AUTO-CONNECT TO PAIRED SYSTEM",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SlateCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Reconnect on Launch & Drop",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (lastConnectedName != null) {
                                    "Target: $lastConnectedName"
                                } else {
                                    "Connect once to save target phone"
                                },
                                color = if (lastConnectedName != null) CyanNeon else TextSecondary,
                                fontSize = 12.sp
                            )
                        }

                        Switch(
                            checked = autoConnectEnabled,
                            onCheckedChange = { onToggleAutoConnect() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = CyanNeon,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = SlateSurface
                            ),
                            modifier = Modifier.testTag("auto_connect_switch")
                        )
                    }

                    if (lastConnectedAddr != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onConnectLastDevice,
                            colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Connect to $lastConnectedName Now",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Audio Quality Mode (Hi-Fi 44.1kHz LDAC Quality vs Standard)
            Text(
                text = "AUDIO QUALITY PROFILE",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            AudioQualityItem(
                quality = AudioQuality.HI_FI_44K,
                isSelected = audioQuality == AudioQuality.HI_FI_44K,
                badge = "RECOMMENDED",
                badgeColor = CyanNeon,
                onSelect = { onSetQuality(AudioQuality.HI_FI_44K) }
            )

            Spacer(modifier = Modifier.height(6.dp))

            AudioQualityItem(
                quality = AudioQuality.STANDARD_16K,
                isSelected = audioQuality == AudioQuality.STANDARD_16K,
                badge = "LOW DATA",
                badgeColor = AmberTransmitting,
                onSelect = { onSetQuality(AudioQuality.STANDARD_16K) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: Audio Output Destination (Headset / 3.5mm jack vs Loudspeaker)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AUDIO OUTPUT DESTINATION",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (isHeadsetPlugged) {
                    Text(
                        text = "🎧 3.5mm Jack Detected",
                        color = GreenConnected,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            AudioRouteItem(
                route = AudioRoute.HEADSET_OR_AUTO,
                icon = Icons.Default.Headphones,
                subtitle = "Enables wired 3.5mm headphones, USB-C DACs, or phone speaker",
                isSelected = audioRoute == AudioRoute.HEADSET_OR_AUTO,
                onSelect = { onSetRoute(AudioRoute.HEADSET_OR_AUTO) }
            )

            Spacer(modifier = Modifier.height(6.dp))

            AudioRouteItem(
                route = AudioRoute.SPEAKERPHONE,
                icon = Icons.Default.VolumeUp,
                subtitle = "Force output through phone's internal loud speaker",
                isSelected = audioRoute == AudioRoute.SPEAKERPHONE,
                onSelect = { onSetRoute(AudioRoute.SPEAKERPHONE) }
            )

            Spacer(modifier = Modifier.height(6.dp))

            AudioRouteItem(
                route = AudioRoute.EARPIECE,
                icon = Icons.Default.PhoneInTalk,
                subtitle = "Private listening through front ear receiver",
                isSelected = audioRoute == AudioRoute.EARPIECE,
                onSelect = { onSetRoute(AudioRoute.EARPIECE) }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun AudioQualityItem(
    quality: AudioQuality,
    isSelected: Boolean,
    badge: String,
    badgeColor: Color,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) SlateSurface else SlateCard
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) CyanNeon else SlateCardBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("quality_item_${quality.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = quality.label,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = quality.description,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = CyanNeon,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun AudioRouteItem(
    route: AudioRoute,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    subtitle: String,
    isSelected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) SlateSurface else SlateCard
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isSelected) GreenConnected else SlateCardBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("route_item_${route.name}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) GreenConnected.copy(alpha = 0.2f) else SlateDark),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) GreenConnected else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = route.label,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = GreenConnected,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
