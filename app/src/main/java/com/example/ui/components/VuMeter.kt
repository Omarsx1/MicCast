package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.SignalRed
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.TextGray
import com.example.ui.theme.TextWhite
import com.example.ui.theme.VuAmber
import com.example.ui.theme.VuGreen
import com.example.ui.theme.VuRed
import com.example.ui.theme.VuYellow
import kotlin.math.roundToInt

@Composable
fun VuMeter(
    dbfs: Float,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    // Smooth animation for audio meter ballistics
    val animatedDbfs by animateFloatAsState(
        targetValue = if (isMuted) -60f else dbfs,
        animationSpec = tween(durationMillis = 65),
        label = "VuMeterAnimation"
    )

    // Normalize from -60 dBFS to 0 dBFS -> 0.0f to 1.0f
    val normalizedLevel = ((animatedDbfs + 60f) / 60f).coerceIn(0f, 1f)
    val isClipping = !isMuted && dbfs >= -0.8f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
            .testTag("vu_meter_container")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "NIVEL DE ENTRADA (VU)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextGray,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                if (isMuted) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SignalRed.copy(alpha = 0.2f))
                            .border(1.dp, SignalRed, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "MUTED",
                            color = SignalRed,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            // Clip LED Indicator
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isClipping) VuRed else Color(0xFF334155))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "CLIP",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isClipping) VuRed else TextGray,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isMuted) "-∞ dB" else "${animatedDbfs.roundToInt()} dBFS",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (isClipping) VuRed else TextWhite
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Professional Segmented Canvas Meter Bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .testTag("vu_meter_bar")
        ) {
            val totalWidth = size.width
            val totalHeight = size.height
            val segmentCount = 36
            val gap = 3.dp.toPx()
            val segmentWidth = (totalWidth - (segmentCount - 1) * gap) / segmentCount
            val activeSegments = (normalizedLevel * segmentCount).roundToInt()

            for (i in 0 until segmentCount) {
                val x = i * (segmentWidth + gap)
                val ratio = i.toFloat() / segmentCount

                // Segment base color according to audio level
                val baseColor = when {
                    ratio > 0.88f -> VuRed
                    ratio > 0.72f -> VuAmber
                    ratio > 0.50f -> VuYellow
                    else -> VuGreen
                }

                val isActive = i < activeSegments && !isMuted
                val fillColor = if (isActive) baseColor else baseColor.copy(alpha = 0.12f)

                drawRoundRect(
                    color = fillColor,
                    topLeft = Offset(x, 0f),
                    size = Size(segmentWidth, totalHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Calibration markers: -60, -36, -18, -12, -6, 0 dB
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val markers = listOf("-60", "-36", "-24", "-12", "-6", "0 dB")
            markers.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = TextGray
                )
            }
        }
    }
}
