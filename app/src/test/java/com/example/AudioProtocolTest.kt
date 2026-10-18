package com.example

import com.example.protocol.AudioProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProtocolTest {

    @Test
    fun testHeaderExact16Bytes() {
        val payload = ByteArray(480) { 0x42 }
        val flags = (AudioProtocol.FLAG_CODEC_OPUS.toInt() or AudioProtocol.FLAG_MUTED.toInt()).toByte()
        val packet = AudioProtocol.packPacket(
            flags = flags,
            sequenceNumber = 1024,
            sampleCounter = 48000L,
            sessionId = 987654321L,
            payload = payload
        )

        // Exact 16 bytes header + 480 bytes payload = 496 bytes
        assertEquals(16 + 480, packet.size)

        val header = AudioProtocol.parseHeader(packet)
        assertNotNull(header)
        header?.let {
            assertEquals(AudioProtocol.PROTOCOL_VERSION, it.version)
            assertEquals(1024, it.sequenceNumber)
            assertEquals(480, it.payloadLength)
            assertEquals(48000L, it.sampleCounter)
            assertEquals(987654321L, it.sessionId)
            assertTrue(it.isOpus)
            assertTrue(it.isMuted)
            assertFalse(it.isStereo)
        }
    }

    @Test
    fun testSequenceRolloverModularArithmetic() {
        // Normal sequence increment
        assertEquals(1, AudioProtocol.sequenceDifference(10, 9))
        assertEquals(5, AudioProtocol.sequenceDifference(15, 10))

        // Rollover from 65535 (0xFFFF) to 0
        assertEquals(1, AudioProtocol.sequenceDifference(0, 65535))
        assertEquals(2, AudioProtocol.sequenceDifference(1, 65535))

        // Out of order (old packet)
        assertEquals(-1, AudioProtocol.sequenceDifference(9, 10))
        assertEquals(-1, AudioProtocol.sequenceDifference(65535, 0))
    }

    @Test
    fun testHmacSha256ChallengeResponse() {
        val pin = "849201"
        val nonce = ByteArray(16) { it.toByte() }

        val hmac1 = AudioProtocol.computeHmacSha256(pin, nonce)
        val hmac2 = AudioProtocol.computeHmacSha256(pin, nonce)

        // Deterministic
        assertArrayEquals(hmac1, hmac2)
        assertEquals(32, hmac1.size) // 256 bits = 32 bytes

        // Wrong pin produces different hash
        val wrongHmac = AudioProtocol.computeHmacSha256("000000", nonce)
        assertFalse(hmac1.contentEquals(wrongHmac))
    }

    @Test
    fun handshakeFrameHasExactLayoutAndLength() {
        val nonce = ByteArray(16) { it.toByte() }
        val deviceId = "device-1234"
        val deviceName = "Pixel 8"
        val credential = "123456".toByteArray(Charsets.UTF_8)

        val frame = AudioProtocol.buildHandshakeFrame(
            mode = AudioProtocol.AUTH_MODE_PIN,
            deviceId = deviceId,
            deviceName = deviceName,
            credential = credential,
            nonce = nonce
        )

        // 4 magic + 3 fixed + 1 idLen + id + 1 nameLen + name + 32 hmac
        val expectedLength = 4 + 3 + 1 + deviceId.length + 1 + deviceName.length + 32
        assertEquals(expectedLength, frame.size)

        assertEquals(0x4D.toByte(), frame[0]) // 'M'
        assertEquals(0x43.toByte(), frame[1]) // 'C'
        assertEquals(0x53.toByte(), frame[2]) // 'S'
        assertEquals(0x43.toByte(), frame[3]) // 'C'
        assertEquals(AudioProtocol.CONTROL_VERSION, frame[4])
        assertEquals(AudioProtocol.AUTH_MODE_PIN, frame[5])
        assertEquals(deviceId.length.toByte(), frame[6])

        val idStart = 7
        assertEquals(deviceId, String(frame, idStart, deviceId.length, Charsets.UTF_8))

        val nameLenIndex = idStart + deviceId.length
        assertEquals(deviceName.length.toByte(), frame[nameLenIndex])

        val nameStart = nameLenIndex + 1
        assertEquals(deviceName, String(frame, nameStart, deviceName.length, Charsets.UTF_8))

        val expectedHmac = AudioProtocol.computeHmacSha256(credential, nonce)
        assertArrayEquals(expectedHmac, frame.copyOfRange(nameStart + deviceName.length, frame.size))
    }
}
