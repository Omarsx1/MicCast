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
    private var deviceListener: AudioObjectPropertyListenerBlock?
    private var didLogFirstRender = false

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
        observeDeviceChanges()
    }

    /// Pure enumeration: safe to call from any thread.
    private func loadDevices() -> [AudioDevice] {
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

        guard status == noErr else { return [] }

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

        return list
    }

    public func refreshDevices() {
        let list = loadDevices()

        let apply = {
            self.availableDevices = list

            if let bh = list.first(where: { $0.isBlackHole }) {
                self.selectDevice(bh)
            } else if self.selectedDevice == nil {
                self.selectedDevice = list.first
            }
        }

        if Thread.isMainThread {
            apply()
        } else {
            DispatchQueue.main.async(execute: apply)
        }
    }

    /// Re-routes the engine when the target device changes, including while running.
    public func selectDevice(_ device: AudioDevice) {
        let changed = self.selectedDevice?.id != device.id
        self.selectedDevice = device

        guard changed, isRunning else { return }

        // The graph is bound to the previous hardware format, so it must be rebuilt
        // rather than re-pointed at a different device.
        engine.stop()
        isRunning = false
        didLogFirstRender = false
        startEngine()
    }

    /// Keeps the output in sync when CoreAudio adds or removes devices
    /// (e.g. BlackHole finishing its load after launch).
    private func observeDeviceChanges() {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )

        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            self?.refreshDevices()
        }

        AudioObjectAddPropertyListenerBlock(
            AudioObjectID(kAudioObjectSystemObject),
            &address,
            DispatchQueue.main,
            block
        )

        self.deviceListener = block
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

        // Resolve the target synchronously. Depending on the async refresh means
        // the very first start would configure no device and silently fall back
        // to the system default output instead of BlackHole.
        if selectedDevice == nil {
            let list = loadDevices()
            availableDevices = list
            selectedDevice = list.first(where: { $0.isBlackHole }) ?? list.first
        }

        startEngine()
    }

    /// Builds the graph with the output device already selected.
    ///
    /// AVAudioEngine binds the output node's hardware format during `connect()`.
    /// Selecting the device afterwards leaves the engine attached to the previous
    /// hardware: `start()` succeeds but the source node is never asked to render,
    /// so the virtual device receives pure silence.
    private func startEngine() {
        engine = AVAudioEngine()

        if let device = selectedDevice {
            setDevice(id: device.id)
        }

        let outputFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false)!

        let node = AVAudioSourceNode { _, _, frameCount, audioBufferList -> OSStatus in
            let ablPointer = UnsafeMutableAudioBufferListPointer(audioBufferList)
            guard let buffer = ablPointer[0].mData?.assumingMemoryBound(to: Float.self) else {
                return noErr
            }

            if !self.didLogFirstRender {
                self.didLogFirstRender = true
                print("[AUDIO OUTPUT] Source node is rendering into BlackHole")
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

        do {
            try engine.start()
            isRunning = true
            print("[AUDIO OUTPUT] AudioEngine started at 48kHz toward \(selectedDevice?.name ?? "Default")")
        } catch {
            isRunning = false
            print("[AUDIO OUTPUT] Engine start failed: \(error.localizedDescription)")
        }
    }

    public func setDevice(id: AudioDeviceID) {
        var deviceID = id
        guard let au = engine.outputNode.audioUnit else {
            print("[AUDIO OUTPUT] Output audio unit unavailable; cannot select device")
            return
        }

        let deviceStatus = AudioUnitSetProperty(
            au,
            kAudioOutputUnitProperty_CurrentDevice,
            kAudioUnitScope_Global,
            0,
            &deviceID,
            UInt32(MemoryLayout<AudioDeviceID>.size)
        )

        guard deviceStatus == noErr else {
            print("[AUDIO OUTPUT] Could not set output device (status \(deviceStatus)); using system default")
            return
        }

        // Request 64 frames for ultra-low latency (~1.33 ms)
        var bufferFrameSize: UInt32 = 64
        let bufferStatus = AudioUnitSetProperty(
            au,
            kAudioDevicePropertyBufferFrameSize,
            kAudioUnitScope_Global,
            0,
            &bufferFrameSize,
            UInt32(MemoryLayout<UInt32>.size)
        )

        if bufferStatus != noErr {
            print("[AUDIO OUTPUT] Could not set buffer size (status \(bufferStatus))")
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
