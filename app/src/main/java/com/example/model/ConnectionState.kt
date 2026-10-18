package com.example.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Discovering : ConnectionState

    data class Connecting(
        val host: String,
        val port: Int,
        val transport: TransportType
    ) : ConnectionState

    data class Handshaking(
        val host: String,
        val step: String
    ) : ConnectionState

    data class Streaming(
        val host: String,
        val port: Int,
        val transport: TransportType,
        val sessionId: Long,
        val rttMs: Int = 0,
        val packetsSent: Long = 0L,
        val bytesSent: Long = 0L,
        val currentDbfs: Float = -60f,
        val isMuted: Boolean = false,
        val gainDb: Float = 0f
    ) : ConnectionState

    data class Error(
        val message: String,
        val retryable: Boolean = true
    ) : ConnectionState
}

data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val isUsb: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)
