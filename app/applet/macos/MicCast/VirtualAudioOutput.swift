import Foundation
import AVFoundation
import AudioToolbox
import CoreAudio

public class VirtualAudioOutput: ObservableObject {
    public static let shared = VirtualAudioOutput()

    @Published public var availableDevices: [AudioDevice] = []
    @Published public var selectedDevice: AudioDevice?
    @Published public var isRunning: Bool = false
    @Published public var currentDbfs: Float = -60.0

    private var engine = AVAudioEngine()
    private var sourceNode: AVAudioSourceNode?

    // Audio Ring Buffer (elastic buffer for jitter compensation)
    private let ringBufferSize: Int = 48000 * 2 // 1 second buffer
    private var ringBuffer: [Int16] = []
    private let bufferLock = NSLock()

    public struct AudioDevice: Identifiable, Hashable {
        public let id: AudioDeviceID
        public let name: String
        public let isBlackHole: Bool
    }

    public init() {
        refreshDevices()
    }

    public func refreshDevices() {
        var propertySize: UInt32 = 0
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )

        let status = AudioObjectGetPropertyDataSize(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &propertySize
        )

        guard status == noErr else { return }

        let deviceCount = Int(propertySize) / MemoryLayout<AudioDeviceID>.size
        var deviceIDs = [AudioDeviceID](repeating: 0, count: deviceCount)

        AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            0,
            nil,
            &propertySize,
            &deviceIDs
        )

        var list: [AudioDevice] = []
        for id in deviceIDs {
            var nameSize: UInt32 = 256
            var name: CFString = "" as CFString
            var nameAddress = AudioObjectPropertyAddress(
                mSelector: kAudioObjectPropertyName,
                mScope: kAudioObjectPropertyScopeGlobal,
                mElement: kAudioObjectPropertyElementMain
            )

            AudioObjectGetPropertyData(id, &nameAddress, 0, nil, &nameSize, &name)
            let deviceName = name as String
            let isBH = deviceName.lowercased().contains("blackhole")
            list.append(AudioDevice(id: id, name: deviceName, isBlackHole: isBH))
        }

        DispatchQueue.main.async {
            self.availableDevices = list
            // Prefer BlackHole if present
            if let bh = list.first(where: { $0.isBlackHole }) {
                self.selectedDevice = bh
            } else if self.selectedDevice == nil {
                self.selectedDevice = list.first
            }
        }
    }

    public func pushAudioSamples(_ pcmBytes: Data) {
        let sampleCount = pcmBytes.count / 2
        var samples = [Int16](repeating: 0, count: sampleCount)
        _ = samples.withUnsafeMutableBytes { pcmBytes.copyBytes(to: $0) }

        // Calculate RMS for live VU Meter
        var sumSquares: Double = 0.0
        for sample in samples {
            let s = Double(sample)
            sumSquares += s * s
        }
        let rms = sqrt(sumSquares / Double(max(1, sampleCount)))
        let dbfs = rms > 0.0 ? max(-60.0, min(0.0, 20.0 * log10(rms / 32767.0))) : -60.0

        DispatchQueue.main.async {
            self.currentDbfs = Float(dbfs)
        }

        bufferLock.lock()
        ringBuffer.append(contentsOf: samples)
        if ringBuffer.count > ringBufferSize {
            ringBuffer.removeFirst(ringBuffer.count - ringBufferSize)
        }
        bufferLock.unlock()
    }

    public func start() {
        guard !isRunning else { return }

        engine = AVAudioEngine()
        let outputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false)!

        let node = AVAudioSourceNode { _, _, frameCount, audioBufferList -> OSStatus in
            let ablPointer = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard let buffer = ablPointer[0].mData?.assumingMemoryBound(to: Float.self) else {
                return noErr
            }

            self.bufferLock.lock()
            let available = self.ringBuffer.count
            let toRead = min(Int(frameCount), available)

            for i in 0..<toRead {
                let sampleInt16 = self.ringBuffer[i]
                buffer[i] = Float(sampleInt16) / 32768.0
            }
            if toRead < Int(frameCount) {
                // Pad underrun with silence
                for i in toRead..<Int(frameCount) {
                    buffer[i] = 0.0
                }
            }
            if toRead > 0 {
                self.ringBuffer.removeFirst(toRead)
            }
            self.bufferLock.unlock()

            return noErr
        }

        self.sourceNode = node
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: outputFormat)

        // Set device if selected
        if let dev = selectedDevice {
            setDevice(id: dev.id)
        }

        do {
            try engine.start()
            isRunning = true
            print("[AUDIO OUTPUT] AudioEngine started at 48kHz toward \(selectedDevice?.name ?? "Default")")
        } catch {
            print("[AUDIO OUTPUT] Engine start failed: \(error.localizedDescription)")
        }
    }

    public func setDevice(id: AudioDeviceID) {
        var deviceID = id
        let outputNode = engine.outputNode
        let audioUnit = outputNode.audioUnit

        if let au = audioUnit {
            AudioUnitSetProperty(
                au,
                kAudioOutputUnitProperty_CurrentDevice,
                kAudioUnitScope_Global,
                0,
                &deviceID,
                UInt32(MemoryLayout<AudioDeviceID>.size)
            )

            // Request 64 frames for ultra-low latency (~1.33 ms)
            var bufferFrameSize: UInt32 = 64
            AudioUnitSetProperty(
                au,
                kAudioDevicePropertyBufferFrameSize,
                kAudioUnitScope_Global,
                0,
                &bufferFrameSize,
                UInt32(MemoryLayout<UInt32>.size)
            )
        }
    }

    public func stop() {
        guard isRunning else { return }
        engine.stop()
        if let node = sourceNode {
            engine.detach(node)
        }
        sourceNode = nil
        isRunning = false
    }
}
