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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.TextGray
import com.example.ui.theme.TextWhite

@Composable
fun UsbModeCard(
    onConnectUsb: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(StudioCardBg)
            .border(1.dp, StudioCardBorder, RoundedCornerShape(20.dp))
            .padding(18.dp)
            .testTag("usb_mode_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(StudioAmber.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cable,
                    contentDescription = null,
                    tint = StudioAmber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "MODO CABLE USB (ULTRABAJA LATENCIA)",
                    style = MaterialTheme.typography.labelSmall,
                    color = StudioAmber,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Latencia sub-15ms sin interferencias de radio",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextGray
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "1. Conecta el teléfono a la Mac por cable USB con Depuración USB activa.",
            style = MaterialTheme.typography.bodySmall,
            color = TextWhite
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "2. En la terminal de la Mac, ejecuta este comando para reenviar los puertos:",
            style = MaterialTheme.typography.bodySmall,
            color = TextWhite
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Code block for adb reverse command
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF090D16))
                .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = null,
                    tint = TextGray,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "adb reverse tcp:51516 tcp:51516 && adb reverse tcp:51517 tcp:51517",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = StudioAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Button(
            onClick = onConnectUsb,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("connect_usb_button"),
            colors = ButtonDefaults.buttonColors(containerColor = StudioAmber),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Cable, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Conectar vía USB (127.0.0.1)",
                color = Color.Black,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
