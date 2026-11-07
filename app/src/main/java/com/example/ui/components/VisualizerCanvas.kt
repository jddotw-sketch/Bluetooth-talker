package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AmberTransmitting
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.GreenConnected
import com.example.ui.theme.SlateLight
import kotlin.math.sin

@Composable
fun VisualizerCanvas(
    isTransmitting: Boolean,
    isReceiving: Boolean,
    txLevel: Float,
    rxLevel: Float,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_idle")
    val idlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "idle_phase"
    )

    val activeLevel = when {
        isTransmitting -> txLevel
        isReceiving -> rxLevel
        else -> 0f
    }

    val barColor = when {
        isTransmitting -> AmberTransmitting
        isReceiving -> GreenConnected
        else -> SlateLight
    }

    val glowColor = when {
        isTransmitting -> AmberTransmitting.copy(alpha = 0.35f)
        isReceiving -> CyanNeon.copy(alpha = 0.35f)
        else -> Color.Transparent
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val numBars = 32
        val spacing = 4.dp.toPx()
        val totalSpacing = spacing * (numBars - 1)
        val barWidth = ((width - totalSpacing) / numBars).coerceAtLeast(3f)

        for (i in 0 until numBars) {
            val normalizedX = i.toFloat() / (numBars - 1) // 0.0 to 1.0
            // Bell curve weight in the center
            val centerWeight = sin(normalizedX * Math.PI).toFloat()

            val barHeight = when {
                activeLevel > 0.02f -> {
                    val variation = sin((normalizedX * 12f) + (activeLevel * 10f)).toFloat() * 0.25f
                    val dynamicFactor = (activeLevel * 1.5f + variation).coerceIn(0.1f, 1.0f)
                    (height * 0.15f + height * 0.75f * dynamicFactor * centerWeight).coerceIn(6.dp.toPx(), height * 0.95f)
                }
                else -> {
                    // Subtle breathing wave when idle
                    val idleWave = sin(normalizedX * 6f + idlePhase).toFloat()
                    4.dp.toPx() + 6.dp.toPx() * (idleWave + 1f) * 0.5f * centerWeight
                }
            }

            val x = i * (barWidth + spacing)
            val top = centerY - (barHeight / 2f)

            // Draw soft background glow for active bars
            if (activeLevel > 0.05f) {
                drawRoundRect(
                    color = glowColor,
                    topLeft = Offset(x - 2f, top - 3f),
                    size = Size(barWidth + 4f, barHeight + 6f),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }

            // Draw primary waveform bar
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        barColor.copy(alpha = if (activeLevel > 0.02f) 1f else 0.4f),
                        barColor.copy(alpha = if (activeLevel > 0.02f) 0.85f else 0.25f)
                    ),
                    startY = top,
                    endY = top + barHeight
                ),
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
