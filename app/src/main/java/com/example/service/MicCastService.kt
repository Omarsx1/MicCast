package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioCaptureEngine
import com.example.model.AudioConfig
import com.example.model.ConnectionState
import com.example.model.TransportType
import com.example.network.AudioStreamer
import com.example.network.AuthMode
import com.example.network.ControlClient
import com.example.network.PairingStore
import com.example.protocol.AudioProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MicCastService : Service() {

    companion object {
        private const val TAG = "MicCastService"
        private const val NOTIFICATION_ID = 51515
        private const val CHANNEL_ID = "miccast_audio_stream"

        const val ACTION_START = "com.example.miccast.START"
        const val ACTION_STOP = "com.example.miccast.STOP"
        const val ACTION_TOGGLE_MUTE = "com.example.miccast.TOGGLE_MUTE"

        const val EXTRA_HOST = "extra_host"
        const val EXTRA_CONTROL_PORT = "extra_control_port"
        const val EXTRA_AUDIO_PORT = "extra_audio_port"
        const val EXTRA_PIN = "extra_pin"
        const val EXTRA_TRANSPORT = "extra_transport"
        const val EXTRA_AUTH_MODE = "extra_auth_mode"
        const val EXTRA_TOKEN = "extra_token"

        // Global StateFlow for UI binding
        private val _serviceConnectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        val serviceConnectionState: StateFlow<ConnectionState> = _serviceConnectionState.asStateFlow()

        private val _currentDbfs = MutableStateFlow(-60f)
        val currentDbfs: StateFlow<Float> = _currentDbfs.asStateFlow()

        private val _isMutedState = MutableStateFlow(false)
        val isMutedState: StateFlow<Boolean> = _isMutedState.asStateFlow()

        private val _gainDbState = MutableStateFlow(0f)
        val gainDbState: StateFlow<Float> = _gainDbState.asStateFlow()
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var controlClient: ControlClient? = null
    private var audioStreamer: AudioStreamer? = null
    private var captureEngine: AudioCaptureEngine? = null
    private var rttMonitorJob: Job? = null

    private var audioConfig = AudioConfig()
    private var activeTransport = TransportType.WIFI_UDP
    private var targetHost = ""
    private var activeSessionId = 0L

    inner class LocalBinder : Binder() {
        fun getService(): MicCastService = this@MicCastService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val controlPort = intent.getIntExtra(EXTRA_CONTROL_PORT, AudioProtocol.DEFAULT_TCP_CONTROL_PORT)
                val audioPort = intent.getIntExtra(EXTRA_AUDIO_PORT, AudioProtocol.DEFAULT_UDP_AUDIO_PORT)
                val transportName = intent.getStringExtra(EXTRA_TRANSPORT) ?: TransportType.WIFI_UDP.name
                val transport = TransportType.valueOf(transportName)

                val authMode = when (intent.getStringExtra(EXTRA_AUTH_MODE)) {
                    AuthMode.TOKEN.name -> AuthMode.TOKEN
                    else -> AuthMode.PIN
                }
                val credential = if (authMode == AuthMode.TOKEN) {
                    intent.getByteArrayExtra(EXTRA_TOKEN) ?: ByteArray(0)
                } else {
                    (intent.getStringExtra(EXTRA_PIN) ?: "").trim().toByteArray(Charsets.UTF_8)
                }

                startStreaming(host, controlPort, audioPort, authMode, credential, transport)
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MicCast::StreamingWakeLock")?.apply {
                acquire(12 * 60 * 60 * 1000L) // 12 hours timeout guard
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MicCast::LowLatencyWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MicCast::HighPerfWifiLock")
            }?.apply {
                acquire()
            }
            Log.d(TAG, "Acquired WakeLock and LowLatency WifiLock")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire system locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, MicCastService::class.java).apply { action = ACTION_TOGGLE_MUTE }
        val pendingMute = PendingIntent.getService(
            this, 1, muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MicCastService::class.java).apply { action = ACTION_STOP }
        val pendingStop = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMuted = _isMutedState.value
        val muteLabel = if (isMuted) getString(R.string.action_unmute) else getString(R.string.action_mute)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingLaunch)
            .setOngoing(true)
            .addAction(0, muteLabel, pendingMute)
            .addAction(0, getString(R.string.action_stop), pendingStop)
            .build()
    }

    @SuppressLint("ForegroundServiceType")
    private fun startStreaming(
        host: String,
        controlPort: Int,
        audioPort: Int,
        authMode: AuthMode,
        credential: ByteArray,
        transport: TransportType
    ) {
        targetHost = host
        activeTransport = transport

        val initialNotification = buildNotification("Conectando con $host...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        serviceScope.launch(Dispatchers.IO) {
            _serviceConnectionState.value = ConnectionState.Connecting(host, controlPort, transport)

            val client = ControlClient()
            controlClient = client

            _serviceConnectionState.value = ConnectionState.Handshaking(
                host,
                if (authMode == AuthMode.TOKEN) "Reconectando dispositivo..." else "Autenticando PIN..."
            )
            val handshakeResult = client.performHandshake(
                host = host,
                port = controlPort,
                mode = authMode,
                credential = credential,
                deviceId = PairingStore.deviceId(this@MicCastService),
                deviceName = PairingStore.deviceName()
            )

            if (handshakeResult.isFailure) {
                // A rejected token means the Mac revoked this device: drop it so the
                // next tap asks for the PIN again.
                if (authMode == AuthMode.TOKEN) {
                    PairingStore.clearToken(this@MicCastService, host)
                }

                val errorMsg = handshakeResult.exceptionOrNull()?.message ?: "Error en autenticación"
                _serviceConnectionState.value = ConnectionState.Error(errorMsg)
                updateNotification("Error: $errorMsg")
                return@launch
            }

            val handshake = handshakeResult.getOrThrow()
            handshake.issuedToken?.let { token ->
                PairingStore.saveToken(this@MicCastService, host, token)
            }

            val sessionId = handshake.sessionId
            activeSessionId = sessionId

            // Determine target audio port based on transport
            val resolvedAudioPort = if (transport == TransportType.USB_TCP) {
                AudioProtocol.DEFAULT_TCP_USB_PORT
            } else {
                audioPort
            }

            val streamer = AudioStreamer(host, resolvedAudioPort, transport, sessionId, audioConfig)
            if (!streamer.start()) {
                _serviceConnectionState.value = ConnectionState.Error("No se pudo iniciar el transmisor de audio")
                updateNotification("Error al abrir canal de audio")
                return@launch
            }
            audioStreamer = streamer

            // Start Audio Capture Engine
            val engine = AudioCaptureEngine(
                config = audioConfig,
                onAudioFrame = { frameBytes, sampleCount, dbfs ->
                    _currentDbfs.value = dbfs
                    streamer.sendAudioFrame(
                        frameData = frameBytes,
                        sampleCount = sampleCount,
                        isMuted = _isMutedState.value,
                        isOpus = audioConfig.codec == com.example.model.AudioCodec.OPUS_5MS
                    )
                },
                onError = { exc ->
                    _serviceConnectionState.value = ConnectionState.Error("Fallo en captura de micrófono: ${exc.message}")
                }
            )
            engine.gainDb = _gainDbState.value
            engine.isMuted = _isMutedState.value
            engine.start()
            captureEngine = engine

            _serviceConnectionState.value = ConnectionState.Streaming(
                host = host,
                port = resolvedAudioPort,
                transport = transport,
                sessionId = sessionId,
                rttMs = 0,
                isMuted = _isMutedState.value,
                gainDb = _gainDbState.value
            )

            updateNotification("Transmitiendo audio (${transport.displayName})")

            // Start periodic RTT monitoring
            startRttMonitoring(client)
        }
    }

    private fun startRttMonitoring(client: ControlClient) {
        rttMonitorJob?.cancel()
        rttMonitorJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1500)
                val rtt = client.measureRtt()
                val current = _serviceConnectionState.value
                if (current is ConnectionState.Streaming) {
                    _serviceConnectionState.value = current.copy(
                        rttMs = rtt,
                        packetsSent = audioStreamer?.totalPacketsSent?.get() ?: 0L,
                        bytesSent = audioStreamer?.totalBytesSent?.get() ?: 0L,
                        currentDbfs = _currentDbfs.value
                    )
                }
            }
        }
    }

    fun setGain(gainDb: Float) {
        _gainDbState.value = gainDb
        captureEngine?.gainDb = gainDb
    }

    fun toggleMute() {
        val newMute = !_isMutedState.value
        _isMutedState.value = newMute
        captureEngine?.isMuted = newMute
        updateNotification(if (newMute) "Micrófono Silenciado" else "Transmitiendo audio (${activeTransport.displayName})")
    }

    fun setMute(muted: Boolean) {
        _isMutedState.value = muted
        captureEngine?.isMuted = muted
        updateNotification(if (muted) "Micrófono Silenciado" else "Transmitiendo audio (${activeTransport.displayName})")
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    fun stopStreaming() {
        rttMonitorJob?.cancel()
        rttMonitorJob = null

        captureEngine?.stop()
        captureEngine = null

        audioStreamer?.stop()
        audioStreamer = null

        controlClient?.close()
        controlClient = null

        _serviceConnectionState.value = ConnectionState.Disconnected
        _currentDbfs.value = -60f

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopStreaming()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }
}
