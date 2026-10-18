package com.example.network

import android.util.Log
import com.example.protocol.AudioProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TCP Control Client for authentication handshake and session negotiation.
 * Handshake flow (v0x03):
 * 1. Connect to TCP Port 51516
 * 2. Mac sends: 16-byte random nonce
 * 3. Client sends: 'MCSC'(4) + version 0x03(1) + mode(1) + idLen(1) + deviceId
 *    + nameLen(1) + name + HMAC-SHA256(PIN or token, nonce)(32)
 * 4. Mac responds: Status 0x00(1) + Session ID uint32(4) + tokenLen(1) + token
 *    The token is present only when the Mac just paired this device (PIN mode).
 * Legacy v0x02 clients keep working against the same server.
 */
enum class AuthMode { PIN, TOKEN }

class ControlClient {
    companion object {
        private const val TAG = "ControlClient"
        const val MAGIC_CONTROL_0: Byte = 0x4D // 'M'
        const val MAGIC_CONTROL_1: Byte = 0x43 // 'C'
        const val MAGIC_CONTROL_2: Byte = 0x53 // 'S'
        const val MAGIC_CONTROL_3: Byte = 0x43 // 'C'

        const val STATUS_OK: Byte = 0x00
        const val STATUS_AUTH_FAILED: Byte = 0x01
        const val STATUS_BUSY: Byte = 0x02
        const val STATUS_LOCKED_OUT: Byte = 0x03
    }

    private var socket: Socket? = null
    private var dataInput: DataInputStream? = null
    private var dataOutput: DataOutputStream? = null

    suspend fun performHandshake(
        host: String,
        port: Int,
        mode: AuthMode,
        credential: ByteArray,
        deviceId: String,
        deviceName: String,
        timeoutMs: Int = 4000
    ): Result<HandshakeResult> = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            // Track the socket before authenticating so any failure closes it
            // instead of leaving a half-open connection on the Mac.
            socket = sock

            val input = DataInputStream(sock.getInputStream())
            val output = DataOutputStream(sock.getOutputStream())

            // 1. Receive 16-byte nonce from server
            val nonce = ByteArray(16)
            input.readFully(nonce)

            // 2. Build and send the v0x03 handshake frame
            val frame = AudioProtocol.buildHandshakeFrame(
                mode = if (mode == AuthMode.PIN) AudioProtocol.AUTH_MODE_PIN else AudioProtocol.AUTH_MODE_TOKEN,
                deviceId = deviceId,
                deviceName = deviceName,
                credential = credential,
                nonce = nonce
            )
            output.write(frame)
            output.flush()

            // 3. Read server status response
            val status = input.readByte()
            when (status) {
                STATUS_OK -> {
                    val sessionIdBytes = ByteArray(4)
                    input.readFully(sessionIdBytes)
                    val sessionId = ByteBuffer.wrap(sessionIdBytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL

                    val tokenLength = input.readByte().toInt() and 0xFF
                    val issuedToken = if (tokenLength > 0) {
                        val tokenBytes = ByteArray(tokenLength)
                        input.readFully(tokenBytes)
                        tokenBytes
                    } else {
                        null
                    }

                    socket = sock
                    dataInput = input
                    dataOutput = output

                    Log.i(TAG, "Handshake successful (${mode.name})! Session ID: $sessionId, new token: ${issuedToken != null}")
                    Result.success(HandshakeResult(sessionId, sock, issuedToken))
                }
                STATUS_AUTH_FAILED -> {
                    sock.close()
                    val message = if (mode == AuthMode.TOKEN) {
                        "Emparejamiento expirado. Ingresa el PIN de nuevo."
                    } else {
                        "PIN incorrecto. Verifica el código mostrado en tu Mac."
                    }
                    Result.failure(SecurityException(message))
                }
                STATUS_LOCKED_OUT -> {
                    sock.close()
                    Result.failure(SecurityException("Demasiados intentos fallidos. Mac bloqueada temporalmente por seguridad."))
                }
                STATUS_BUSY -> {
                    sock.close()
                    Result.failure(IllegalStateException("La Mac ya tiene otra sesión de audio activa."))
                }
                else -> {
                    sock.close()
                    Result.failure(IllegalStateException("Respuesta de servidor no reconocida (código $status)."))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Control handshake failed: ${e.message}", e)
            close()
            Result.failure(e)
        }
    }

    /**
     * Measures Round-Trip-Time (RTT) via TCP keepalive echo.
     */
    suspend fun measureRtt(): Int = withContext(Dispatchers.IO) {
        val out = dataOutput ?: return@withContext 0
        val inp = dataInput ?: return@withContext 0
        try {
            val t0 = System.currentTimeMillis()
            out.writeByte(0x7F) // Ping op
            out.flush()
            val ack = inp.readByte()
            if (ack == 0x7E.toByte()) {
                val t1 = System.currentTimeMillis()
                (t1 - t0).toInt()
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        dataInput = null
        dataOutput = null
    }

    data class HandshakeResult(
        val sessionId: Long,
        val controlSocket: Socket,
        val issuedToken: ByteArray? = null
    )
}
