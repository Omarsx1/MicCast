package com.example.network

import android.util.Log
import com.example.model.AudioConfig
import com.example.model.TransportType
import com.example.protocol.AudioProtocol
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance audio packet transmitter supporting UDP (Wi-Fi) and TCP (USB adb reverse).
 */
class AudioStreamer(
    private val host: String,
    private val port: Int,
    private val transportType: TransportType,
    private val sessionId: Long,
    private val audioConfig: AudioConfig
) {
    companion object {
        private const val TAG = "AudioStreamer"
    }

    private val isRunning = AtomicBoolean(false)
    private var sequenceNumber = 0
    private val sampleCounter = AtomicLong(0L)
    val totalPacketsSent = AtomicLong(0L)
    val totalBytesSent = AtomicLong(0L)

    // UDP resources
    private var udpSocket: DatagramSocket? = null
    private var inetAddress: InetAddress? = null

    // TCP resources (USB adb reverse)
    private var tcpSocket: Socket? = null
    private var tcpOutputStream: OutputStream? = null

    // Reusable packet buffer to prevent GC allocations
    private val packetBuffer = ByteBuffer.allocateDirect(AudioProtocol.HEADER_SIZE + audioConfig.bytesPerFrame)
        .order(ByteOrder.BIG_ENDIAN)
    private val rawArray = ByteArray(AudioProtocol.HEADER_SIZE + audioConfig.bytesPerFrame)

    fun start(): Boolean {
        if (isRunning.getAndSet(true)) return true

        return try {
            when (transportType) {
                TransportType.WIFI_UDP -> {
                    inetAddress = InetAddress.getByName(host)
                    val sock = DatagramSocket()
                    sock.trafficClass = 0x10 or 0x08 // IPTOS_LOWDELAY | IPTOS_THROUGHPUT
                    sock.sendBufferSize = 64 * 1024
                    udpSocket = sock
                }
                TransportType.USB_TCP -> {
                    val sock = Socket()
                    sock.tcpNoDelay = true // Disable Nagle's algorithm for instant transmission
                    sock.sendBufferSize = 64 * 1024
                    sock.connect(InetSocketAddress(host, port), 3000)
                    tcpSocket = sock
                    tcpOutputStream = sock.getOutputStream()
                }
            }
            Log.i(TAG, "AudioStreamer started on $transportType to $host:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioStreamer: ${e.message}", e)
            isRunning.set(false)
            false
        }
    }

    fun sendAudioFrame(frameData: ByteArray, sampleCount: Int, isMuted: Boolean, isOpus: Boolean) {
        if (!isRunning.get()) return

        try {
            var flags: Byte = 0
            if (isOpus) flags = (flags.toInt() or AudioProtocol.FLAG_CODEC_OPUS.toInt()).toByte()
            if (isMuted) flags = (flags.toInt() or AudioProtocol.FLAG_MUTED.toInt()).toByte()

            val currentSeq = sequenceNumber++ and 0xFFFF
            val currentSamples = sampleCounter.getAndAdd(sampleCount.toLong()) and 0xFFFFFFFFL
            val payloadLen = frameData.size

            synchronized(packetBuffer) {
                packetBuffer.clear()
                AudioProtocol.writeHeader(
                    packetBuffer,
                    flags = flags,
                    sequenceNumber = currentSeq,
                    sampleCounter = currentSamples,
                    sessionId = sessionId,
                    payloadLength = payloadLen
                )
                packetBuffer.position(AudioProtocol.HEADER_SIZE)
                packetBuffer.put(frameData, 0, payloadLen)

                val totalSize = AudioProtocol.HEADER_SIZE + payloadLen

                when (transportType) {
                    TransportType.WIFI_UDP -> {
                        packetBuffer.position(0)
                        packetBuffer.get(rawArray, 0, totalSize)
                        val packet = DatagramPacket(rawArray, totalSize, inetAddress, port)
                        udpSocket?.send(packet)
                    }
                    TransportType.USB_TCP -> {
                        packetBuffer.position(0)
                        packetBuffer.get(rawArray, 0, totalSize)
                        tcpOutputStream?.write(rawArray, 0, totalSize)
                        tcpOutputStream?.flush()
                    }
                }

                totalPacketsSent.incrementAndGet()
                totalBytesSent.addAndGet(totalSize.toLong())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending audio frame: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        try {
            tcpOutputStream?.close()
            tcpSocket?.close()
        } catch (_: Exception) {}
        tcpSocket = null
        tcpOutputStream = null
    }
}
