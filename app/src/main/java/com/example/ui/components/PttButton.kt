package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.WalkieMode
import com.example.ui.theme.AmberGlow
import com.example.ui.theme.AmberTransmitting
import com.example.ui.theme.BlueAccent
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.GreenConnected
import com.example.ui.theme.RedMuted
import com.example.ui.theme.SlateCard
import com.example.ui.theme.SlateCardBorder
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PttButton(
    walkieMode: WalkieMode,
    isTransmitting: Boolean,
    isReceiving: Boolean,
    isMuted: Boolean,
    isConnected: Boolean,
    isLoopbackActive: Boolean,
    txLevel: Float,
    onPttPress: () -> Unit,
    onPttRelease: () -> Unit,
    onOpenMicToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current

    // Infinite pulsing rings when transmitting
    val infiniteTransition = rememberInfiniteTransition(label = "ptt_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isTransmitting) 0.94f else 1.0f,
        animationSpec = tween(120),
        label = "button_scale"
    )

    val coreBgColor by animateColorAsState(
        targetValue = when {
            isTransmitting -> AmberTransmitting
            isReceiving -> GreenConnected
            isMuted -> RedMuted
            walkieMode == WalkieMode.OPEN_MIC -> CyanNeon.copy(alpha = 0.85f)
            isConnected || isLoopbackActive -> BlueAccent
            else -> SlateSurface
        },
        label = "core_bg"
    )

    val coreBorderColor by animateColorAsState(
        targetValue = when {
            isTransmitting -> AmberTransmitting
            isReceiving -> GreenConnected
            walkieMode == WalkieMode.OPEN_MIC -> CyanNeon
            else -> SlateCardBorder
        },
        label = "core_border"
    )

    Box(
        modifier = modifier
            .size(240.dp)
            .testTag("ptt_container"),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulsing ring when transmitting
        if (isTransmitting) {
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .scale(pulseScale + (txLevel * 0.2f))
                    .clip(CircleShape)
                    .background(AmberGlow.copy(alpha = pulseAlpha))
            )
        }

        // Secondary ring
        Box(
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            coreBorderColor.copy(alpha = 0.25f),
                            Color.Transparent
                        )
                    )
                )
                .border(2.dp, coreBorderColor.copy(alpha = 0.4f), CircleShape)
        )

        // Main tactile button
        Box(
            modifier = Modifier
                .size(164.dp)
                .scale(buttonScale)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = if (isTransmitting) {
                            listOf(AmberTransmitting, Color(0xFFE65100))
                        } else {
                            listOf(SlateCard, SlateDark)
                        }
                    )
                )
                .border(3.dp, coreBorderColor, CircleShape)
                .testTag("ptt_button")
                .pointerInput(walkieMode, isConnected, isLoopbackActive) {
                    if (walkieMode == WalkieMode.PUSH_TO_TALK) {
                        detectTapGestures(
                            onPress = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onPttPress()
                                try {
                                    awaitRelease()
                                } finally {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
                                    onPttRelease()
                                }
                            }
                        )
                    } else {
                        // OPEN_MIC Mode: tap to toggle mic
                        detectTapGestures(
                            onTap = {
                                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                onOpenMicToggle()
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = when {
                        isMuted -> Icons.Default.MicOff
                        isTransmitting -> Icons.Default.Radio
                        else -> Icons.Default.Mic
                    },
                    contentDescription = "Microphone State",
                    tint = if (isTransmitting) Color.Black else TextPrimary,
                    modifier = Modifier.size(46.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = when {
                        isTransmitting -> "LIVE ON AIR"
                        isReceiving -> "RECEIVING"
                        walkieMode == WalkieMode.OPEN_MIC -> "TAP TO TALK"
                        else -> "HOLD TO TALK"
                    },
                    color = if (isTransmitting) Color.Black else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )

                Text(
                    text = when {
                        isTransmitting -> "TRANSMITTING"
                        walkieMode == WalkieMode.OPEN_MIC -> "OPEN MIC"
                        else -> "PTT MODE"
                    },
                    color = if (isTransmitting) Color.Black.copy(alpha = 0.75f) else TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}
