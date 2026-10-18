package com.example.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * MicCast Binary Protocol v2 Specification
 *
 * Exact 16-byte fixed header:
 * [0..1]   Magic: 'M', 'C' (0x4D, 0x43)
 * [2]      Version: 0x02
 * [3]      Flags:
 *            bit 0: Codec (0 = PCM 16-bit, 1 = Opus)
 *            bit 1: Channels (0 = Mono, 1 = Stereo)
 *            bit 2: Muted (1 = Audio is muted/silenced)
 *            bit 3: Encrypted (1 = Payload encrypted)
 *            bit 4: Heartbeat / Ping-Pong (1 = Ping/Pong RTT probe)
 * [4..5]   Sequence Number: uint16 (0..65535, rollover safe)
 * [6..7]   Payload Length: uint16 (bytes in this packet payload)
 * [8..11]  Sample Counter: uint32 (accumulated 48kHz sample count)
 * [12..15] Session ID: uint32 (assigned during TCP handshake)
 */
object AudioProtocol {
    const val MAGIC_0: Byte = 0x4D // 'M'
    const val MAGIC_1: Byte = 0x43 // 'C'
    const val PROTOCOL_VERSION: Byte = 0x02
    const val HEADER_SIZE = 16

    // Ports
    const val DEFAULT_UDP_AUDIO_PORT = 51515
    const val DEFAULT_TCP_CONTROL_PORT = 51516
    const val DEFAULT_TCP_USB_PORT = 51517

    // Flag bit masks
    const val FLAG_CODEC_OPUS: Byte = 0x01
    const val FLAG_CHANNELS_STEREO: Byte = 0x02
    const val FLAG_MUTED: Byte = 0x04
    const val FLAG_ENCRYPTED: Byte = 0x08
    const val FLAG_HEARTBEAT: Byte = 0x10

    /**
     * Packs header and payload into a byte array.
     */
    fun packPacket(
        flags: Byte,
        sequenceNumber: Int,
        sampleCounter: Long,
        sessionId: Long,
        payload: ByteArray,
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payloadLength)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_0)
        buffer.put(MAGIC_1)
        buffer.put(PROTOCOL_VERSION)
        buffer.put(flags)
        buffer.putShort((sequenceNumber and 0xFFFF).toShort())
        buffer.putShort((payloadLength and 0xFFFF).toShort())
        buffer.putInt((sampleCounter and 0xFFFFFFFFL).toInt())
        buffer.putInt((sessionId and 0xFFFFFFFFL).toInt())
        buffer.put(payload, payloadOffset, payloadLength)

        return buffer.array()
    }

    /**
     * Encodes header in-place in a reusable buffer to avoid allocations.
     */
    fun writeHeader(
        buffer: ByteBuffer,
        flags: Byte,
        sequenceNumber: Int,
        sampleCounter: Long,
        sessionId: Long,
        payloadLength: Int
    ) {
        buffer.put(0, MAGIC_0)
        buffer.put(1, MAGIC_1)
        buffer.put(2, PROTOCOL_VERSION)
        buffer.put(3, flags)
        buffer.putShort(4, (sequenceNumber and 0xFFFF).toShort())
        buffer.putShort(6, (payloadLength and 0xFFFF).toShort())
        buffer.putInt(8, (sampleCounter and 0xFFFFFFFFL).toInt())
        buffer.putInt(12, (sessionId and 0xFFFFFFFFL).toInt())
    }

    /**
     * Validates and parses the 16-byte header.
     */
    fun parseHeader(packetData: ByteArray, offset: Int = 0, length: Int = packetData.size): Header? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(packetData, offset, length).order(ByteOrder.BIG_ENDIAN)

        val m0 = buf.get()
        val m1 = buf.get()
        if (m0 != MAGIC_0 || m1 != MAGIC_1) return null

        val version = buf.get()
        if (version != PROTOCOL_VERSION) return null

        val flags = buf.get()
        val sequence = buf.short.toInt() and 0xFFFF
        val payloadLen = buf.short.toInt() and 0xFFFF
        val sampleCounter = buf.int.toLong() and 0xFFFFFFFFL
        val sessionId = buf.int.toLong() and 0xFFFFFFFFL

        return Header(
            version = version,
            flags = flags,
            sequenceNumber = sequence,
            payloadLength = payloadLen,
            sampleCounter = sampleCounter,
            sessionId = sessionId,
            isOpus = (flags.toInt() and FLAG_CODEC_OPUS.toInt()) != 0,
            isStereo = (flags.toInt() and FLAG_CHANNELS_STEREO.toInt()) != 0,
            isMuted = (flags.toInt() and FLAG_MUTED.toInt()) != 0,
            isEncrypted = (flags.toInt() and FLAG_ENCRYPTED.toInt()) != 0,
            isHeartbeat = (flags.toInt() and FLAG_HEARTBEAT.toInt()) != 0
        )
    }

    /**
     * Challenge-Response: computes HMAC-SHA256(PIN, nonce).
     */
    fun computeHmacSha256(pin: String, nonce: ByteArray): ByteArray =
        computeHmacSha256(pin.toByteArray(Charsets.UTF_8), nonce)

    fun computeHmacSha256(key: ByteArray, nonce: ByteArray): ByteArray {
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(nonce)
    }

    // Control channel: v0x03 adds per-device token authentication.
    const val CONTROL_VERSION: Byte = 0x03
    const val AUTH_MODE_PIN: Byte = 0x00
    const val AUTH_MODE_TOKEN: Byte = 0x01
    const val MAX_DEVICE_ID_LEN = 64
    const val MAX_DEVICE_NAME_LEN = 64

    /**
     * Builds the v0x03 control handshake frame:
     * 'MCSC' + version + mode + idLen + deviceId + nameLen + name + HMAC(credential, nonce)
     */
    fun buildHandshakeFrame(
        mode: Byte,
        deviceId: String,
        deviceName: String,
        credential: ByteArray,
        nonce: ByteArray
    ): ByteArray {
        val idBytes = deviceId.toByteArray(Charsets.UTF_8)
        require(idBytes.isNotEmpty() && idBytes.size <= MAX_DEVICE_ID_LEN) {
            "deviceId must be 1..$MAX_DEVICE_ID_LEN bytes"
        }

        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
            .let { if (it.size > MAX_DEVICE_NAME_LEN) it.copyOf(MAX_DEVICE_NAME_LEN) else it }

        val hmac = computeHmacSha256(credential, nonce)

        val buffer = ByteBuffer.allocate(4 + 3 + idBytes.size + nameBytes.size + hmac.size)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(0x4D) // 'M'
        buffer.put(0x43) // 'C'
        buffer.put(0x53) // 'S'
        buffer.put(0x43) // 'C'
        buffer.put(CONTROL_VERSION)
        buffer.put(mode)
        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        buffer.put(hmac)

        return buffer.array()
    }

    /**
     * Evaluates sequence number diff with uint16 modular arithmetic.
     * Returns positive if newSeq is ahead of lastSeq, accounting for 65536 rollover.
     */
    fun sequenceDifference(newSeq: Int, lastSeq: Int): Int {
        val diff = (newSeq - lastSeq) and 0xFFFF
        return if (diff < 32768) diff else diff - 65536
    }

    data class Header(
        val version: Byte,
        val flags: Byte,
        val sequenceNumber: Int,
        val payloadLength: Int,
        val sampleCounter: Long,
        val sessionId: Long,
        val isOpus: Boolean,
        val isStereo: Boolean,
        val isMuted: Boolean,
        val isEncrypted: Boolean,
        val isHeartbeat: Boolean
    )
}
