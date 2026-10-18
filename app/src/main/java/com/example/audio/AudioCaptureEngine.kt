package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.example.model.AudioConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Ultra-low latency Audio Capture Engine for Android.
 * Runs on a dedicated real-time audio thread with THREAD_PRIORITY_URGENT_AUDIO.
 */
class AudioCaptureEngine(
    private val config: AudioConfig,
    private val onAudioFrame: (frameBytes: ByteArray, sampleCount: Int, dbfs: Float) -> Unit,
    private val onError: (Exception) -> Unit
) {
    companion object {
        private const val TAG = "AudioCaptureEngine"
    }

    private val isRecording = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    var gainDb: Float = config.gainDb

    @Volatile
    var isMuted: Boolean = config.isMuted

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.getAndSet(true)) return

        val sampleRate = config.sampleRate
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        // Ensure buffer is large enough for system driver, but read in small 5ms frames
        val internalBufferSize = max(minBufSize, config.bytesPerFrame * 4)

        try {
            // Try VOICE_COMMUNICATION first (hardware echo cancellation/tuning), fallback to MIC
            var record: AudioRecord? = null
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER
            )

            for (source in sources) {
                try {
                    val candidate = AudioRecord(
                        source,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        internalBufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        record = candidate
                        break
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Source $source initialization failed: ${e.message}")
                }
            }

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                isRecording.set(false)
                onError(IllegalStateException("No se pudo inicializar la captura de audio en hardware"))
                return
            }

            audioRecord = record
            record.startRecording()

            captureThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val samplesPerFrame = config.samplesPerFrame
                val bytesPerFrame = config.bytesPerFrame
                val shortBuffer = ShortArray(samplesPerFrame)
                val byteBuffer = ByteArray(bytesPerFrame)

                while (isRecording.get()) {
                    val readSamples = record.read(shortBuffer, 0, samplesPerFrame)
                    if (readSamples <= 0) {
                        if (readSamples == AudioRecord.ERROR_INVALID_OPERATION || readSamples == AudioRecord.ERROR_BAD_VALUE) {
                            Log.e(TAG, "AudioRecord read error: $readSamples")
                        }
                        continue
                    }

                    // Process gain & mute, calculate RMS
                    val gainMultiplier = 10f.pow(gainDb / 20f)
                    var sumSquare = 0.0

                    val currentlyMuted = isMuted

                    for (i in 0 until readSamples) {
                        var sample = shortBuffer[i].toFloat()
                        if (currentlyMuted) {
                            sample = 0f
                        } else {
                            sample *= gainMultiplier
                            // Hard clipping protection (-32768 to 32767)
                            sample = max(-32768f, min(32767f, sample))
                        }

                        val shortVal = sample.toInt().toShort()
                        sumSquare += (shortVal * shortVal).toDouble()

                        // Little endian conversion to byte array
                        val byteIndex = i * 2
                        byteBuffer[byteIndex] = (shortVal.toInt() and 0xFF).toByte()
                        byteBuffer[byteIndex + 1] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
                    }

                    // Calculate RMS and convert to dBFS
                    val rms = sqrt(sumSquare / readSamples)
                    val dbfs = if (rms > 0.0) {
                        val db = (20.0 * log10(rms / 32767.0)).toFloat()
                        max(-60f, min(0f, db))
                    } else {
                        -60f
                    }

                    onAudioFrame(byteBuffer, readSamples, dbfs)
                }
            }, "MicCastAudioCaptureThread").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            isRecording.set(false)
            Log.e(TAG, "Failed to start audio capture: ${e.message}", e)
            onError(e)
        }
    }

    fun stop() {
        isRecording.set(false)
        try {
            captureThread?.interrupt()
            captureThread?.join(300)
        } catch (_: Exception) {}
        captureThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
