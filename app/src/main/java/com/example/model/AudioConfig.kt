package com.example.model

enum class AudioCodec(val displayName: String, val bitFlag: Byte) {
    PCM_16BIT("PCM 16-bit (Mínima latencia)", 0x00),
    OPUS_5MS("Opus 5ms (Bajo ancho de banda)", 0x01)
}

enum class TransportType(val displayName: String) {
    WIFI_UDP("Wi-Fi (UDP)"),
    USB_TCP("USB (TCP vía ADB)")
}

data class AudioConfig(
    val sampleRate: Int = 48000,
    val frameDurationMs: Int = 5,
    val channels: Int = 1, // Mono
    val codec: AudioCodec = AudioCodec.PCM_16BIT,
    val gainDb: Float = 0f, // -6 dB to +18 dB
    val isMuted: Boolean = false,
    val bitRateKbps: Int = 96
) {
    // 48000 Hz * 0.005s = 240 samples per frame
    val samplesPerFrame: Int = (sampleRate * frameDurationMs) / 1000
    // 240 samples * 2 bytes per sample (16-bit mono) = 480 bytes
    val bytesPerFrame: Int = samplesPerFrame * channels * 2
}
