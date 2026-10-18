package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.example.model.DiscoveredDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class DeviceDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "DeviceDiscovery"
        const val SERVICE_TYPE_TCP = "_miccast._tcp."
        const val SERVICE_TYPE_UDP = "_miccast._udp."
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        try {
            // Acquire MulticastLock so Android doesn't filter out mDNS broadcast packets
            multicastLock = wifiManager?.createMulticastLock("MicCastMulticast")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "Discovery started for $serviceType")
                    isDiscovering = true
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service found: ${serviceInfo.serviceName}")
                    try {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                            }

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                val host = resolvedInfo.host ?: return
                                val hostAddress = host.hostAddress ?: return
                                val port = resolvedInfo.port
                                val name = resolvedInfo.serviceName

                                val device = DiscoveredDevice(
                                    name = name,
                                    host = hostAddress,
                                    port = port,
                                    isUsb = hostAddress == "127.0.0.1" || hostAddress == "localhost"
                                )

                                val current = _discoveredDevices.value.toMutableList()
                                current.removeAll { it.host == device.host }
                                current.add(device)
                                _discoveredDevices.value = current
                                Log.i(TAG, "Resolved device: $name ($hostAddress:$port)")
                            }
                        })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error initiating resolve: ${e.message}")
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                    val current = _discoveredDevices.value.toMutableList()
                    current.removeAll { it.name == serviceInfo.serviceName }
                    _discoveredDevices.value = current
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Discovery stopped: $serviceType")
                    isDiscovering = false
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Start discovery failed: $errorCode")
                    stopDiscovery()
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.e(TAG, "Stop discovery failed: $errorCode")
                }
            }

            discoveryListener = listener
            nsdManager.discoverServices(SERVICE_TYPE_TCP, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery: ${e.message}", e)
        }
    }

    fun stopDiscovery() {
        if (!isDiscovering) return
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        discoveryListener = null
        isDiscovering = false

        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    fun addManualDevice(host: String, port: Int, name: String = "Mac Manual") {
        val device = DiscoveredDevice(
            name = name,
            host = host,
            port = port,
            isUsb = host == "127.0.0.1" || host == "localhost"
        )
        val current = _discoveredDevices.value.toMutableList()
        current.removeAll { it.host == host }
        current.add(0, device)
        _discoveredDevices.value = current
    }
}
