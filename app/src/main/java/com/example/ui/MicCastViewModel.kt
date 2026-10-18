package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.AudioCodec
import com.example.model.AudioConfig
import com.example.model.ConnectionState
import com.example.model.DiscoveredDevice
import com.example.model.TransportType
import com.example.network.AuthMode
import com.example.network.DeviceDiscovery
import com.example.network.PairingStore
import com.example.protocol.AudioProtocol
import com.example.service.MicCastService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MicCastViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val discovery = DeviceDiscovery(context)

    val connectionState: StateFlow<ConnectionState> = MicCastService.serviceConnectionState
    val currentDbfs: StateFlow<Float> = MicCastService.currentDbfs
    val isMuted: StateFlow<Boolean> = MicCastService.isMutedState
    val gainDb: StateFlow<Float> = MicCastService.gainDbState

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = discovery.discoveredDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _selectedCodec = MutableStateFlow(AudioCodec.PCM_16BIT)
    val selectedCodec: StateFlow<AudioCodec> = _selectedCodec.asStateFlow()

    private val _pendingPinDevice = MutableStateFlow<DiscoveredDevice?>(null)
    val pendingPinDevice: StateFlow<DiscoveredDevice?> = _pendingPinDevice.asStateFlow()

    private val _showManualConnectDialog = MutableStateFlow(false)
    val showManualConnectDialog: StateFlow<Boolean> = _showManualConnectDialog.asStateFlow()

    private val _lastManualHost = MutableStateFlow("192.168.1.")
    val lastManualHost: StateFlow<String> = _lastManualHost.asStateFlow()

    init {
        startDiscovery()
    }

    fun startDiscovery() {
        _isDiscovering.value = true
        discovery.startDiscovery()
    }

    fun refreshDiscovery() {
        discovery.stopDiscovery()
        discovery.startDiscovery()
        _isDiscovering.value = true
    }

    fun onDeviceClicked(device: DiscoveredDevice) {
        if (connectWithStoredToken(device)) return
        _pendingPinDevice.value = device
    }

    fun dismissPinDialog() {
        _pendingPinDevice.value = null
    }

    fun openManualConnect() {
        _showManualConnectDialog.value = true
    }

    fun dismissManualConnect() {
        _showManualConnectDialog.value = false
    }

    fun onManualConnectConfirmed(host: String, port: Int) {
        _lastManualHost.value = host
        _showManualConnectDialog.value = false
        val manualDevice = DiscoveredDevice(
            name = "Mac ($host)",
            host = host,
            port = port,
            isUsb = host == "127.0.0.1" || host == "localhost"
        )
        discovery.addManualDevice(host, port, "Mac ($host)")
        _pendingPinDevice.value = manualDevice
    }

    fun onConnectUsbClicked() {
        val usbDevice = DiscoveredDevice(
            name = "Mac (USB ADB)",
            host = "127.0.0.1",
            port = AudioProtocol.DEFAULT_TCP_CONTROL_PORT,
            isUsb = true
        )
        if (connectWithStoredToken(usbDevice)) return
        _pendingPinDevice.value = usbDevice
    }

    fun submitPinAndConnect(pin: String) {
        val device = _pendingPinDevice.value ?: return
        _pendingPinDevice.value = null

        startConnection(device, AuthMode.PIN, pin.trim().toByteArray(Charsets.UTF_8))
    }

    /**
     * Reconnects without asking for the PIN when this phone already holds a token
     * issued by that Mac. Returns false when there is nothing stored.
     */
    private fun connectWithStoredToken(device: DiscoveredDevice): Boolean {
        val token = PairingStore.token(context, device.host) ?: return false
        startConnection(device, AuthMode.TOKEN, token)
        return true
    }

    private fun startConnection(device: DiscoveredDevice, mode: AuthMode, credential: ByteArray) {
        val transport = if (device.isUsb) TransportType.USB_TCP else TransportType.WIFI_UDP

        val intent = Intent(context, MicCastService::class.java).apply {
            action = MicCastService.ACTION_START
            putExtra(MicCastService.EXTRA_HOST, device.host)
            putExtra(MicCastService.EXTRA_CONTROL_PORT, device.port)
            putExtra(
                MicCastService.EXTRA_AUDIO_PORT,
                if (device.isUsb) AudioProtocol.DEFAULT_TCP_USB_PORT else AudioProtocol.DEFAULT_UDP_AUDIO_PORT
            )
            putExtra(MicCastService.EXTRA_TRANSPORT, transport.name)
            putExtra(MicCastService.EXTRA_AUTH_MODE, mode.name)

            if (mode == AuthMode.TOKEN) {
                putExtra(MicCastService.EXTRA_TOKEN, credential)
            } else {
                putExtra(MicCastService.EXTRA_PIN, String(credential, Charsets.UTF_8))
            }
        }
        context.startForegroundService(intent)
    }

    fun disconnect() {
        val intent = Intent(context, MicCastService::class.java).apply {
            action = MicCastService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun toggleMute() {
        val intent = Intent(context, MicCastService::class.java).apply {
            action = MicCastService.ACTION_TOGGLE_MUTE
        }
        context.startService(intent)
    }

    fun setGain(gain: Float) {
        // Find bound service or update state
        // Handled via ViewModel -> Service binder if available, or static state
    }

    fun setCodec(codec: AudioCodec) {
        _selectedCodec.value = codec
    }

    override fun onCleared() {
        discovery.stopDiscovery()
        super.onCleared()
    }
}
