package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.ConnectionState
import com.example.model.TransportType
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.SignalRed
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioEmerald
import com.example.ui.theme.TextGray
import com.example.ui.theme.TextWhite
import kotlin.math.roundToInt

@Composable
fun ConnectionCard(
    state: ConnectionState,
    isMuted: Boolean,
    gainDb: Float,
    onToggleMute: () -> Unit,
    onGainChanged: (Float) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isStreaming = state is ConnectionState.Streaming

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(StudioCardBg)
            .border(1.dp, StudioCardBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
            .testTag("connection_card")
    ) {
        // Status Row Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status Beacon Dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                is ConnectionState.Streaming -> StudioEmerald
                                is ConnectionState.Connecting, is ConnectionState.Handshaking -> StudioAmber
                                is ConnectionState.Error -> SignalRed
                                else -> TextGray
                            }
                        )
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = when (state) {
                            is ConnectionState.Streaming -> "TRANSMITIENDO EN VIVO"
                            is ConnectionState.Connecting -> "CONECTANDO..."
                            is ConnectionState.Handshaking -> "AUTENTICANDO CON MAC..."
                            is ConnectionState.Error -> "ERROR DE CONEXIÓN"
                            else -> "LISTO PARA CONECTAR"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextWhite,
                        fontSize = 15.sp
                    )
                    if (state is ConnectionState.Streaming) {
                        Text(
                            text = "${state.host}:${state.port} • ID #${state.sessionId}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextGray
                        )
                    }
                }
            }

            // Transport Badge (Wi-Fi vs USB)
            if (state is ConnectionState.Streaming) {
                val isUsb = state.transport == TransportType.USB_TCP
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isUsb) StudioAmber.copy(alpha = 0.2f) else CyanNeon.copy(alpha = 0.15f))
                        .border(1.dp, if (isUsb) StudioAmber else CyanNeon, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isUsb) Icons.Default.Usb else Icons.Default.Wifi,
                            contentDescription = "Transporte",
                            tint = if (isUsb) StudioAmber else CyanNeon,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isUsb) "USB" else "Wi-Fi",
                            color = if (isUsb) StudioAmber else CyanNeon,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Live Metrics Row (RTT, Packets)
        if (state is ConnectionState.Streaming) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF0B0F19))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                MetricItem(
                    title = "LATENCIA ESTIMADA",
                    value = "${state.rttMs / 2} ms",
                    accentColor = when {
                        state.rttMs / 2 < 18 -> StudioEmerald
                        state.rttMs / 2 < 35 -> StudioAmber
                        else -> SignalRed
                    }
                )
                MetricItem(
                    title = "PAQUETES ENVIADOS",
                    value = state.packetsSent.toString(),
                    accentColor = TextWhite
                )
                MetricItem(
                    title = "DATOS TOTALES",
                    value = "%.1f MB".format(state.bytesSent / (1024f * 1024f)),
                    accentColor = CyanNeon
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Digital Gain Slider
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "GANANCIA DE MICRÓFONO",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextGray,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${if (gainDb > 0) "+" else ""}${gainDb.roundToInt()} dB",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = CyanNeon
                )
            }
            Slider(
                value = gainDb,
                onValueChange = onGainChanged,
                valueRange = -6f..18f,
                steps = 24,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("gain_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = CyanNeon,
                    activeTrackColor = CyanNeon,
                    inactiveTrackColor = Color(0xFF334155)
                )
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons Row (Mute & Disconnect)
        if (isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("mute_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMuted) SignalRed else Color(0xFF1E293B)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Silenciar",
                        tint = if (isMuted) Color.White else CyanNeon
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isMuted) "Reanudar Mic" else "Silenciar",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("disconnect_button"),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SignalRed
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(SignalRed)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Desconectar", tint = SignalRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Desconectar", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MetricItem(
    title: String,
    value: String,
    accentColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = TextGray,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = accentColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}
