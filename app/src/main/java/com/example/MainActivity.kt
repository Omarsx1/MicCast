package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.AudioCodec
import com.example.model.ConnectionState
import com.example.service.MicCastService
import com.example.ui.MicCastViewModel
import com.example.ui.components.ConnectionCard
import com.example.ui.components.MacDeviceList
import com.example.ui.components.ManualConnectDialog
import com.example.ui.components.PinInputDialog
import com.example.ui.components.UsbModeCard
import com.example.ui.components.VuMeter
import com.example.ui.theme.CyanNeon
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.StudioAmber
import com.example.ui.theme.StudioCardBg
import com.example.ui.theme.StudioCardBorder
import com.example.ui.theme.StudioDarkBg
import com.example.ui.theme.TextGray
import com.example.ui.theme.TextWhite

class MainActivity : ComponentActivity() {

    private val viewModel: MicCastViewModel by viewModels()
    private var boundService: MicCastService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MicCastService.LocalBinder
            boundService = binder?.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val serviceIntent = Intent(this, MicCastService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme {
                MicCastMainScreen(
                    viewModel = viewModel,
                    onGainChanged = { gain -> boundService?.setGain(gain) }
                )
            }
        }
    }

    override fun onDestroy() {
        try {
            unbindService(serviceConnection)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}

@Composable
fun MicCastMainScreen(
    viewModel: MicCastViewModel,
    onGainChanged: (Float) -> Unit
) {
    val context = LocalContext.current

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val currentDbfs by viewModel.currentDbfs.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val gainDb by viewModel.gainDb.collectAsStateWithLifecycle()
    val discoveredDevices by viewModel.discoveredDevices.collectAsStateWithLifecycle()
    val isDiscovering by viewModel.isDiscovering.collectAsStateWithLifecycle()
    val selectedCodec by viewModel.selectedCodec.collectAsStateWithLifecycle()

    val pendingPinDevice by viewModel.pendingPinDevice.collectAsStateWithLifecycle()
    val showManualConnectDialog by viewModel.showManualConnectDialog.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Permission Launchers
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Handled */ }

    LaunchedEffect(Unit) {
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(StudioDarkBg),
        containerColor = StudioDarkBg
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // App Header
            item {
                Spacer(modifier = Modifier.height(10.dp))
                HeaderSection()
            }

            // Real-time VU Meter (Active whenever app is running)
            item {
                VuMeter(
                    dbfs = currentDbfs,
                    isMuted = isMuted
                )
            }

            // Connection Status & Active Audio Controls Card
            item {
                ConnectionCard(
                    state = connectionState,
                    isMuted = isMuted,
                    gainDb = gainDb,
                    onToggleMute = { viewModel.toggleMute() },
                    onGainChanged = onGainChanged,
                    onDisconnect = { viewModel.disconnect() }
                )
            }

            // Navigation Tabs (Only when not actively streaming)
            if (connectionState !is ConnectionState.Streaming) {
                item {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF0F172A),
                        contentColor = CyanNeon,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, StudioCardBorder, RoundedCornerShape(12.dp)),
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = CyanNeon,
                                height = 3.dp
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Wi-Fi (mDNS)", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Cable USB", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Cable, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("Calidad / Códec", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }

                when (selectedTab) {
                    0 -> {
                        item {
                            MacDeviceList(
                                devices = discoveredDevices,
                                isDiscovering = isDiscovering,
                                onRefresh = { viewModel.refreshDiscovery() },
                                onSelectDevice = { device -> viewModel.onDeviceClicked(device) }
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = { viewModel.openManualConnect() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("manual_ip_button"),
                                shape = RoundedCornerShape(12.dp),
                                border = ButtonDefaults.outlinedButtonBorder.copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(StudioCardBorder)
                                )
                            ) {
                                Icon(Icons.Default.Lan, contentDescription = null, tint = CyanNeon)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Conectar manualmente por IP...", color = TextWhite)
                            }
                        }
                    }
                    1 -> {
                        item {
                            UsbModeCard(
                                onConnectUsb = { viewModel.onConnectUsbClicked() }
                            )
                        }
                    }
                    2 -> {
                        item {
                            CodecSettingsCard(
                                selectedCodec = selectedCodec,
                                onCodecSelected = { viewModel.setCodec(it) }
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // PIN Authentication Dialog
    pendingPinDevice?.let { device ->
        PinInputDialog(
            hostName = device.name,
            onDismiss = { viewModel.dismissPinDialog() },
            onSubmitPin = { pin -> viewModel.submitPinAndConnect(pin) }
        )
    }

    // Manual Connect Dialog
    if (showManualConnectDialog) {
        ManualConnectDialog(
            onDismiss = { viewModel.dismissManualConnect() },
            onConfirm = { host, port -> viewModel.onManualConnectConfirmed(host, port) }
        )
    }
}

@Composable
private fun HeaderSection() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(CyanNeon.copy(alpha = 0.3f), Color(0xFF0F172A))
                    )
                )
                .border(1.5.dp, CyanNeon, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = CyanNeon,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "MicCast",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = TextWhite,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(CyanNeon.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "PRO",
                        color = CyanNeon,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            Text(
                text = "Micrófono inalámbrico y USB de ultrabaja latencia para Mac",
                style = MaterialTheme.typography.bodySmall,
                color = TextGray
            )
        }
    }
}

@Composable
private fun CodecSettingsCard(
    selectedCodec: AudioCodec,
    onCodecSelected: (AudioCodec) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(containerColor = StudioCardBg)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "FORMATO DE TRANSMISIÓN DE AUDIO",
                style = MaterialTheme.typography.labelSmall,
                color = TextGray,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            AudioCodec.values().forEach { codec ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedCodec == codec,
                        onClick = { onCodecSelected(codec) },
                        colors = RadioButtonDefaults.colors(selectedColor = CyanNeon)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = codec.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextWhite
                        )
                        Text(
                            text = when (codec) {
                                AudioCodec.PCM_16BIT -> "48 kHz, 16-bit Mono sin compresión. 0 ms de lookahead algorítmico (~768 kbps)."
                                AudioCodec.OPUS_5MS -> "Tramas ultra-cortas de 5 ms a 48 kHz. Ideal para redes Wi-Fi congestionadas (~96 kbps)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}
